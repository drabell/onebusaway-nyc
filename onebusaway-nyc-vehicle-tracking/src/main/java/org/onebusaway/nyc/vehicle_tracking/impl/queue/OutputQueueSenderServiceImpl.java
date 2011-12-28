/**
 * Copyright (c) 2011 Metropolitan Transportation Authority
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.onebusaway.nyc.vehicle_tracking.impl.queue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.TimerTask;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.nyc.queue.DNSResolver;
import org.onebusaway.nyc.transit_data.model.NycQueuedInferredLocationBean;
import org.onebusaway.nyc.transit_data.services.ConfigurationService;
import org.onebusaway.nyc.vehicle_tracking.services.queue.OutputQueueSenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import org.zeromq.ZMQ;

public class OutputQueueSenderServiceImpl implements OutputQueueSenderService {

  private static Logger _log = LoggerFactory.getLogger(OutputQueueSenderServiceImpl.class);

	private static final long HEARTBEAT_INTERVAL = 1000l;

	private static final String HEARTBEAT_TOPIC = "heartbeat";

  private ExecutorService _executorService = null;

  private ExecutorService _heartbeatService = null;

  private ArrayBlockingQueue<String> _outputBuffer = new ArrayBlockingQueue<String>(100);

  private boolean _initialized = false;

  public boolean _isPrimaryInferenceInstance = true;

  public String _primaryHostname = null;

  private ObjectMapper _mapper = new ObjectMapper();		

	protected DNSResolver _outputQueueResolver = null;

	protected DNSResolver _primaryResolver = null;

	protected ZMQ.Context _context = null;

	protected ZMQ.Socket _socket = null;

  @Autowired
  private ConfigurationService _configurationService;

	@Autowired
	private ThreadPoolTaskScheduler _taskScheduler;

  private class SendThread implements Runnable {

    int processedCount = 0;

    Date markTimestamp = new Date();

    private ZMQ.Socket _zmqSocket = null;

    private byte[] _topicName = null;

    public SendThread(ZMQ.Socket socket, String topicName) {
      _zmqSocket = socket;
      _topicName = topicName.getBytes();
    }

    @Override
    public void run() {
      while(true) {		    	
        String r = _outputBuffer.poll();
        if(r == null)
          continue;

				if (_isPrimaryInferenceInstance) {
					_zmqSocket.send(_topicName, ZMQ.SNDMORE);
					_zmqSocket.send(r.getBytes(), 0);
				}

        Thread.yield();

        if(processedCount > 50) {
          _log.warn("Inference output queue(primary=" + _isPrimaryInferenceInstance 
							+ "): processed 50 messages in " 
              + (new Date().getTime() - markTimestamp.getTime()) / 1000 + 
              " seconds; current queue length is " + _outputBuffer.size());

          markTimestamp = new Date();
          processedCount = 0;
        }

        processedCount++;		    	
      }
    }
  }	

  private class HeartbeatThread implements Runnable {

    private ZMQ.Socket _zmqSocket = null;

    private byte[] _topicName = null;

		private long _interval;

    public HeartbeatThread(ZMQ.Socket socket, String topicName, long interval) {
      _zmqSocket = socket;
      _topicName = topicName.getBytes();
			_interval = interval;
    }

    @Override
    public void run() {
			long markTimestamp = System.currentTimeMillis();

			while (true) {
				if (_isPrimaryInferenceInstance) {
					String msg = getHeartbeatMessage(getPrimaryHostname(),
																					 markTimestamp,
																					 _interval);
					_zmqSocket.send(_topicName, ZMQ.SNDMORE);
					_zmqSocket.send(msg.getBytes(), 0);
				}
				try {
					Thread.sleep(_interval);
				} catch (InterruptedException ie) {
					// bury
				}
			}
    }

		private String getHeartbeatMessage(String hostname, long timestamp, long interval) {
			final String msg = "{\"heartbeat\": {\"hostname\":\"%1$s\",\"heartbeat_timestamp\":%2$s,\"heartbeat_interval\":%3$s}}";
			return String.format(msg, getPrimaryHostname(), timestamp, interval);
		}
  }	

	private class OutputQueueCheckThread extends TimerTask {

		@Override
  	public void run() {
			try {
				if (_outputQueueResolver.hasAddressChanged()) {
					_log.warn("Resolver Changed");
					reinitializeQueue();
				}
			} catch (Exception e) {
				_log.error(e.toString());
			}
		}
	}

	private class PrimaryCheckThread extends TimerTask {

		@Override
		public void run() {
			try {
				boolean primaryValue = _primaryResolver.isPrimary();
				if (primaryValue != _isPrimaryInferenceInstance) {
					_log.warn("Primary inference status changed to " + primaryValue);
					_isPrimaryInferenceInstance = primaryValue;
				}
			} catch (Exception e) {
				_log.error(e.toString());
			}
		}
	}

  @Override
  public void enqueue(NycQueuedInferredLocationBean r) {
    try {
      StringWriter sw = new StringWriter();
      MappingJsonFactory jsonFactory = new MappingJsonFactory();
      JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(sw);
      _mapper.writeValue(jsonGenerator, r);
      sw.close();			

      _outputBuffer.put(sw.toString());
    } catch(IOException e) {
      _log.info("Could not serialize inferred location record: " + e.getMessage()); 
    } catch(InterruptedException e) {
      // discard if thread is interrupted or serialization fails
      return;
    }
  }

  @PostConstruct
  public void setup() {
		_outputQueueResolver = new DNSResolver(getQueueHost());
		OutputQueueCheckThread outputQueueCheckThread = new OutputQueueCheckThread();
		// every 10 seconds
		_taskScheduler.scheduleWithFixedDelay(outputQueueCheckThread, 10*1000);

		if (getPrimaryHostname() != null && getPrimaryHostname().length() > 0) {
			_primaryResolver = new DNSResolver(getPrimaryHostname());
			_log.warn("Primary Inference instance configured to be " + getPrimaryHostname() + " on " + _primaryResolver.getLocalHostString());
			PrimaryCheckThread primaryCheckThread = new PrimaryCheckThread();
			_taskScheduler.scheduleWithFixedDelay(primaryCheckThread, 10*1000);
		}
    _executorService = Executors.newFixedThreadPool(1);
		_heartbeatService = Executors.newFixedThreadPool(1);
    startListenerThread();
  }

  @PreDestroy 
  public void destroy() {
    _executorService.shutdownNow();
		_heartbeatService.shutdownNow();
  }

  @Refreshable(dependsOn = {"inference-engine.outputQueueHost", 
      "inference-engine.outputQueuePort", "inference-engine.outputQueueName"})
  public void startListenerThread() {
    if(_initialized == true) {
      _log.warn("Configuration service tried to reconfigure inference output queue service; this service is not reconfigurable once started.");
      return;
    }

    String host = getQueueHost();
    String queueName = getQueueName();
    Integer port = getQueuePort();

    if(host == null || queueName == null || port == null) {
      _log.info("Inference output queue is not attached; output hostname was not available via configuration service.");
      return;
    }

		initializeQueue(host, queueName, port);

  }

	protected void reinitializeQueue() {
		initializeQueue(getQueueHost(),
										getQueueName(),
										getQueuePort());
	}

	protected void initializeQueue(String host, String queueName, Integer port) {
    String bind = "tcp://" + host + ":" + port;
		_log.warn("binding to " + bind);

		if (_context == null) {
			_context = ZMQ.context(1);
		}
		synchronized (_context) {
			if (_socket != null) {
				_executorService.shutdownNow();
				_heartbeatService.shutdownNow();
				_socket.close();
				_executorService = Executors.newFixedThreadPool(1);
				_heartbeatService = Executors.newFixedThreadPool(1);
			}

			_socket = _context.socket(ZMQ.PUB);	    	
			_socket.connect(bind);
			_executorService.execute(new SendThread(_socket, queueName));
			_heartbeatService.execute(new HeartbeatThread(_socket, HEARTBEAT_TOPIC, HEARTBEAT_INTERVAL));

		}

    _log.debug("Inference output queue is sending to " + bind);
    _initialized = true;
	}

	public String getQueueHost() {
		return _configurationService.getConfigurationValueAsString("inference-engine.outputQueueHost", null);
	}

	public String getQueueName() {
		return _configurationService.getConfigurationValueAsString("inference-engine.outputQueueName", null);
	}

	public Integer getQueuePort() {
		return _configurationService.getConfigurationValueAsInteger("inference-engine.outputQueuePort", 5566);
	}

  @Override
  public void setIsPrimaryInferenceInstance(boolean isPrimary) {
    _isPrimaryInferenceInstance = isPrimary;		
  }

  @Override
  public boolean getIsPrimaryInferenceInstance() {
    return _isPrimaryInferenceInstance;
  }	

	@Override
  public void setPrimaryHostname(String hostname) {
		_primaryHostname = hostname;
	}

	@Override
	public String getPrimaryHostname() {
		return _primaryHostname;
	}


}
