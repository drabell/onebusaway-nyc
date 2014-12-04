package org.onebusaway.nyc.admin.service.bundle.impl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.onebusaway.nyc.admin.service.BundleStagerService;
import org.onebusaway.nyc.admin.service.bundle.BundleStager;
import org.onebusaway.nyc.transit_data_manager.bundle.BundleProvider;
import org.onebusaway.nyc.transit_data_manager.bundle.BundlesListMessage;
import org.onebusaway.nyc.transit_data_manager.bundle.model.Bundle;
import org.onebusaway.nyc.transit_data_manager.bundle.model.BundleStatus;
import org.onebusaway.nyc.transit_data_manager.json.JsonTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.google.gson.JsonParser;
import com.sun.jersey.core.header.ContentDisposition;

@Component
@Scope("singleton")
public class LocalBundleStagerServiceImpl implements BundleStagerService{

    private static Logger _log = LoggerFactory.getLogger(LocalBundleStagerServiceImpl.class);

    private ExecutorService _executorService = null;
    
    @Autowired
    @Qualifier("bundleStagingProvider")
    private BundleProvider bundleProvider;
    
    @Autowired
    private BundleStager bundleStager;
    
    @Autowired
    private JsonTool jsonTool;

    private Map<String, BundleStatus> _stageMap = new HashMap<String, BundleStatus>();
    private Integer jobCounter = 0;

    @PostConstruct
    public void setup() {
        _executorService = Executors.newFixedThreadPool(1);
    }
    
    @PreDestroy
    public void stop() {
      _executorService.shutdownNow();
    }
    
    public BundleStatus lookupStagedRequest(String id) {
      return _stageMap.get(id);
    }
    
    public void setBundleProvider(BundleProvider bundleProvider) {
      this.bundleProvider = bundleProvider;
    }

    public void setJsonTool(JsonTool jsonTool) {
      this.jsonTool = jsonTool;
    }
    

    /**
     * request bundles at /var/lib/obanyc/bundles/staged/{environment} be staged
     * @param environment string representing environment (dev/staging/prod/qa)
     * @return status object with id for querying status
     */
    @Override
    public Response stage(String environment, String bundleDir, String bundlePath) {
      _log.info("Starting staging(" + environment + ")...");
      BundleStatus status = new BundleStatus();
      status.setId(getNextId());
      _stageMap.put(status.getId(), status);
      _executorService.execute(new StageThread(status, environment, bundleDir, bundlePath));
      _log.info("stage request complete");

      try {
        String jsonStatus = jsonSerializer(status);
        return Response.ok(jsonStatus).build();
      } catch (Exception e) {
        _log.error("exception serializing response:", e);
      }
      return Response.serverError().build();
    }

    /**
     * query the status of a requested bundle deployment
     * @param id the id of a BundleStatus
     * @return a serialized version of the requested BundleStatus, null otherwise
     */
    @Override
    public Response stageStatus(String id) {
      BundleStatus status = this.lookupStagedRequest(id);
      try {
        String jsonStatus = jsonSerializer(status);
        return Response.ok(jsonStatus).build();
      } catch (Exception e) {
        _log.error("exception serializing response:", e);
      }
      return Response.serverError().build();
    }
    
    @Override
    public Response getBundleList() {
      _log.info("Starting getBundleList.");
  
      List<Bundle> bundles = bundleProvider.getBundles();
  
      Response response;
  
      if (bundles != null) {
        BundlesListMessage bundlesMessage = new BundlesListMessage();
        bundlesMessage.setBundles(bundles);
        bundlesMessage.setStatus("OK");
  
        final BundlesListMessage bundlesMessageToWrite = bundlesMessage;
  
        StreamingOutput output = new StreamingOutput() {
  
          @Override
          public void write(OutputStream out) throws IOException,
              WebApplicationException {
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(out));
  
            jsonTool.writeJson(writer, bundlesMessageToWrite);
  
            writer.close();
            out.close();
  
          }
        };
        response = Response.ok(output, "application/json").build();
      } else {
        response = Response.serverError().build();
      }

      _log.info("Returning Response in getBundleList.");
      return response;
    }
    
    private String getBundleId(File bundleDir) {
      try {
        String bundleId = new JsonParser().parse(
            new FileReader(bundleDir.getAbsolutePath() + File.separator
                + "outputs" + File.separator + "metadata.json")).getAsJsonObject().get(
            "id").getAsString();
        return bundleId;
      } catch (Exception e) {
      }
      return null;
    }
    
    @Override
    public Response getArchiveBundleList() {
      JSONArray response = new JSONArray();
      try {
        for (File datasetDir : new File(bundleStager.getBuiltBundleDirectory()).listFiles()) {
          String buildsDir = datasetDir.getAbsolutePath() + File.separator
              + "builds";
          try {
            for (File bundleDir : new File(buildsDir).listFiles()) {
              JSONObject bundleResponse = new JSONObject();
              bundleResponse.put("id", getBundleId(bundleDir));
              bundleResponse.put("dataset", datasetDir.getName());
              bundleResponse.put("name", bundleDir.getName());
              response.put(bundleResponse);
            }
          } catch (Exception e1) {
            _log.error("Failed to read from: " + buildsDir);
          }
        }
        return Response.ok(response.toString(), "application/json").build();
      } catch (Exception e2) {
        return Response.serverError().build();
      }
    }
    
    @Override
    public Response getFileByName(String dataset, String name, String file) {
      try {
        return Response.ok(
            new File(bundleStager.getBuiltBundleDirectory() + "/" + dataset
                + "/builds/" + name + "/" + file), "application/json").build();
      } catch (Exception e) {
        return Response.serverError().build();
      }
    }
    
    @Override
    public Response getFileById(String id, String file) {
      try {
        for (File datasetDir : new File(bundleStager.getBuiltBundleDirectory()).listFiles()) {
          File buildsDir = new File(datasetDir.getAbsolutePath() + "/builds");
          if (buildsDir.exists() && buildsDir.listFiles().length > 0){
            for (File bundleDir : buildsDir.listFiles()) {
              try {
                if (getBundleId(bundleDir).equals(id)) {
                  String filepath = bundleDir.getAbsolutePath() + "/" + file;
                  return Response.ok(new File(filepath), "application/json").build();
                }
              } catch (Exception e1) {}
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
      return Response.serverError().build();
    }

    @Override
    public Response getBundleFile(String bundleId, String relativeFilename) {

      _log.info("starting getBundleFile for relative filename " + relativeFilename + " in bundle " + bundleId);

      boolean requestIsForValidBundleFile = bundleProvider.checkIsValidBundleFile(
          bundleId, relativeFilename);
      if (!requestIsForValidBundleFile) {
        throw new WebApplicationException(new IllegalArgumentException(
            relativeFilename + " is not listed in bundle metadata."),
            Response.Status.BAD_REQUEST);
      }

      final File requestedFile;
      try {
        requestedFile = bundleProvider.getBundleFile(bundleId, relativeFilename);

      } catch (FileNotFoundException e) {
        _log.info("FileNotFoundException loading " + relativeFilename + " in "
            + bundleId + " bundle.");
        throw new WebApplicationException(e,
            Response.Status.INTERNAL_SERVER_ERROR);
      }

      long fileLength = requestedFile.length();

      StreamingOutput output = new StreamingOutput() {

        @Override
        public void write(OutputStream os) throws IOException,
            WebApplicationException {

          FileChannel inChannel = null;
          WritableByteChannel outChannel = null;

          try {
            inChannel = new FileInputStream(requestedFile).getChannel();
            outChannel = Channels.newChannel(os);

            inChannel.transferTo(0, inChannel.size(), outChannel);
          } finally {
            if (outChannel != null)
              outChannel.close();
            if (inChannel != null)
              inChannel.close();
          }

        }
      };

      ContentDisposition cd = ContentDisposition.type("file").fileName(
          requestedFile.getName()).build();

      Response response = Response.ok(output, MediaType.APPLICATION_OCTET_STREAM).header(
          "Content-Disposition", cd).header("Content-Length", fileLength).build();

      _log.info("Returning Response in getBundleFile");

      return response;
    }

    
    /**
     * Trivial implementation of creating unique Ids. Security is not a
     * requirement here.
     */
    private String getNextId() {
      return "" + inc();
    }

    private Integer inc() {
      synchronized (jobCounter) {
        jobCounter++;
      }
      return jobCounter;
    }
    
    /*private String getBundleDirectory() {
      return bundleStager.getStagedBundleDirectory();
    }*/
    
    private String jsonSerializer(Object object) throws IOException{
      //serialize the status object and send to client -- it contains an id for querying
      final StringWriter sw = new StringWriter();
      final MappingJsonFactory jsonFactory = new MappingJsonFactory();
      final JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(sw);
      ObjectMapper mapper = new ObjectMapper();
      mapper.writeValue(jsonGenerator, object);
      return sw.toString();
    }
    
    /**
     * Thread to perform the actual deployment of the bundle.
     *
     */
    private class StageThread implements Runnable {
      private BundleStatus status;
      private String environment;
      private String bundleDir;
      private String bundleName;
      
      public StageThread(BundleStatus status, String environment, String bundleDir, String bundleName){
        this.status = status;
        this.environment = environment;
        this.bundleDir = bundleDir;
        this.bundleName = bundleName;
      }
      
      @Override
      public void run() {
        bundleStager.stage(status, environment, bundleDir, bundleName);
      }
    }
}
