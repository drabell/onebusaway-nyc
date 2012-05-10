package org.onebusaway.nyc.webapp.actions.api.model;

import org.onebusaway.nyc.presentation.model.SearchResult;
import org.onebusaway.transit_data.model.StopBean;

import java.util.List;

/**
 * A stop as a top-level search result.
 * 
 * @author jmaki
 * 
 */
public class StopResult implements SearchResult {

	private StopBean stop;

	private List<RouteAtStop> routesAvailable;
	
	private Double distanceToQueryLocation = null;

	public StopResult(StopBean stop, List<RouteAtStop> routesAvailable) {
		this.stop = stop;
		this.routesAvailable = routesAvailable;
	}

	public String getId() {
		return stop.getId();
	}

	public String getName() {
		return stop.getName();
	}

	public Double getLatitude() {
		return stop.getLat();
	}

	public Double getLongitude() {
		return stop.getLon();
	}

	public String getStopDirection() {
		if (stop.getDirection() == null || (stop.getDirection() != null && stop.getDirection().equals("?"))) {
			return "unknown";
		} else {
			return stop.getDirection();
		}
	}

	public List<RouteAtStop> getRoutesAvailable() {
		return routesAvailable;
	}

	@Override
	public void setDistanceToQueryLocation(Double distance) {
		this.distanceToQueryLocation = distance;
	}

	@Override
	public Double getDistanceToQueryLocation() {
		return this.distanceToQueryLocation;
	}

}
