package org.onebusaway.nyc.transit_data_federation.impl.tdm;

import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.nyc.transit_data.services.ConfigurationService;
import org.onebusaway.nyc.transit_data_federation.services.tdm.VehicleAssignmentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;

@Component
public class VehicleAssignmentServiceImpl implements VehicleAssignmentService {

  private static Logger _log = LoggerFactory.getLogger(VehicleAssignmentServiceImpl.class);

  private Timer _updateTimer = null;

  @Autowired
  private ConfigurationService _configurationService;

  private volatile HashMap<String, ArrayList<AgencyAndId>> _depotToVehicleListMap = new HashMap<String, ArrayList<AgencyAndId>>();

  private volatile Map<AgencyAndId, String> _vehicleToDepotMap = new HashMap<AgencyAndId, String>();

  private TransitDataManagerApiLibrary _apiLibrary = new TransitDataManagerApiLibrary();

  public void setApiLibrary(TransitDataManagerApiLibrary apiLibrary) {
    this._apiLibrary = apiLibrary;
  }

  private ArrayList<AgencyAndId> getVehicleListForDepot(String depotId) {
    try {
      List<Map<String, String>> vehicleAssignments = _apiLibrary.getItems(
          "depot", depotId, "vehicles", "list");

      ArrayList<AgencyAndId> vehiclesForThisDepot = new ArrayList<AgencyAndId>();
      for (Map<String, String> depotVehicleAssignment : vehicleAssignments) {
        AgencyAndId vehicle = new AgencyAndId(
            depotVehicleAssignment.get("agency-id"),
            depotVehicleAssignment.get("vehicle-id"));
        vehiclesForThisDepot.add(vehicle);
      }

      return vehiclesForThisDepot;
    } catch (Exception e) {
      _log.error("Error getting vehicle list for depot with ID " + depotId);
      return null;
    }
  }

  private class UpdateThread extends TimerTask {
    @Override
    public void run() {
      synchronized (_depotToVehicleListMap) {
        for (String depotId : _depotToVehicleListMap.keySet()) {
          ArrayList<AgencyAndId> list = getVehicleListForDepot(depotId);
          if (list != null) {
            _depotToVehicleListMap.put(depotId, list);

            // build reverse map of vehicleId->depotId for this depotId 
            synchronized(_vehicleToDepotMap) {
              // remove all vehicles assigned to the depot we're "refreshing"
              for(AgencyAndId vehicleId : _vehicleToDepotMap.keySet()) {
                String depotIdOfVehicle = _vehicleToDepotMap.get(vehicleId);
                if(depotIdOfVehicle.equals(depotId)) {
                  _vehicleToDepotMap.remove(vehicleId);
                }
              }

              // add vehicles back that are (still) assigned to the depot
              for(AgencyAndId vehicleId : list) {
                _vehicleToDepotMap.put(vehicleId, depotId);
              }
            }
          }
        } // for
      }
    }
  }

  @Refreshable(dependsOn = "tdm.vehicleAssignmentRefreshInterval")
  public void configChanged() {
    Integer updateInterval = _configurationService.getConfigurationValueAsInteger(
        "tdm.vehicleAssignmentRefreshInterval", null);

    if (updateInterval != null)
      setUpdateFrequency(updateInterval);
  }

  public void setUpdateFrequency(int seconds) {
    if (_updateTimer != null) {
      _updateTimer.cancel();
    }

    _updateTimer = new Timer();
    _updateTimer.schedule(new UpdateThread(), 0, seconds * 1000);
  }

  @SuppressWarnings("unused")
  @PostConstruct
  private void startUpdateProcess() {
    setUpdateFrequency(1 * 24 * 60 * 60); // 1h
  }

  @Override
  public ArrayList<AgencyAndId> getAssignedVehicleIdsForDepot(String depotId) 
      throws Exception {
    
    synchronized (_depotToVehicleListMap) {
      ArrayList<AgencyAndId> list = _depotToVehicleListMap.get(depotId);
      if (list == null) {
        list = getVehicleListForDepot(depotId);
        if(list == null) 
          throw new Exception("Vehicle assignment service is temporarily unavailable.");

        _depotToVehicleListMap.put(depotId, list);
      }
      return list;
    }
  }

  @Override
  public String getAssignedDepotForVehicleId(AgencyAndId vehicle) {
    synchronized(_vehicleToDepotMap) {
      return _vehicleToDepotMap.get(vehicle);
    }
  }

}
