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
package org.onebusaway.nyc.vehicle_tracking.impl.inference;

import org.onebusaway.nyc.transit_data_federation.impl.nyc.RunServiceImpl;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.rules.RunLikelihood;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockStateObservation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.JourneyState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState;
import org.onebusaway.realtime.api.EVehiclePhase;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockTripEntry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JourneyStateTransitionModel {

  /*
   * Time that the vehicle needs to be not moving in order to be a layover.
   */
  private static final double LAYOVER_WAIT_TIME = 120d;

  @Autowired
  public void setBlockStateTransitionModel(
      BlockStateTransitionModel blockStateTransitionModel) {
  }

  private VehicleStateLibrary _vehicleStateLibrary;
  private BlocksFromObservationService _blocksFromObservationService;

  @Autowired
  public void setVehicleStateLibrary(VehicleStateLibrary vehicleStateLibrary) {
    _vehicleStateLibrary = vehicleStateLibrary;
  }

  @Autowired
  public void setBlocksFromObservationService(
      BlocksFromObservationService blocksFromObservationService) {
    _blocksFromObservationService = blocksFromObservationService;
  }

  public static boolean isDetour(boolean currentStateIsSnapped,
      boolean hasSnappedStates, VehicleState parentState) {

    if (parentState == null || parentState.getBlockStateObservation() == null
        || currentStateIsSnapped != Boolean.FALSE || hasSnappedStates)
      return false;

    /*
     * If it was previously snapped, in-service and in the middle of a block
     * (true if this was called) and the current state is not snapped, or it was
     * in detour and it's still not snapped, then it's a detour.
     */
    if (parentState.getJourneyState().isDetour()
        || (parentState.getBlockStateObservation().isSnapped()
            && parentState.getBlockStateObservation().isOnTrip() && parentState.getJourneyState().getPhase() == EVehiclePhase.IN_PROGRESS)) {
      return true;
    }

    return false;
  }

  /**
   * Determine the conditions necessary to say it's stopped at a layover.
   * 
   * @param vehicleNotMoved
   * @param timeDelta
   */
  public static boolean isLayoverStopped(boolean vehicleNotMoved,
      Observation obs, VehicleState parentState) {

    if (parentState == null || !vehicleNotMoved)
      return false;

    final long secondsNotInMotion = (obs.getTime() - parentState.getMotionState().getLastInMotionTime()) / 1000;

    if (secondsNotInMotion >= LAYOVER_WAIT_TIME)
      return true;

    return false;
  }

  /*
   * A deterministic journey state logic.<br>
   */
  public JourneyState getJourneyState(BlockStateObservation blockState,
      VehicleState parentState, Observation obs, boolean vehicleNotMoved) {

    if (_vehicleStateLibrary.isAtBase(obs.getLocation()))
      return JourneyState.atBase();

    final boolean isLayoverStopped = isLayoverStopped(vehicleNotMoved, obs,
        parentState);
    final boolean hasSnappedStates = _blocksFromObservationService.hasSnappedBlockStates(obs);

    if (blockState != null) {
      final double distanceAlong = blockState.getBlockState().getBlockLocation().getDistanceAlongBlock();
      if (distanceAlong <= 0.0) {
        if (isLayoverStopped && blockState.isAtPotentialLayoverSpot()) {
          return JourneyState.layoverBefore();
        } else {
          return JourneyState.deadheadBefore(null);
        }
      } else if (distanceAlong >= blockState.getBlockState().getBlockInstance().getBlock().getTotalBlockDistance()
          && !MotionModelImpl.hasRunChanged(parentState.getBlockStateObservation(),
              blockState)) {
        return JourneyState.deadheadAfter();
      } else {
        /*
         * In the middle of a block.
         */
        final boolean isDetour = isDetour(blockState.isSnapped(),
            hasSnappedStates, parentState);
        if (isLayoverStopped && blockState.isAtPotentialLayoverSpot()) {
          return JourneyState.layoverDuring(isDetour);
        } else {
          if (blockState.isOnTrip()) {
            if (isDetour)
              return JourneyState.deadheadDuring(true);
            else if (obs.hasOutOfServiceDsc())
              return JourneyState.deadheadDuring(isDetour);
            else
              return JourneyState.inProgress();
          } else {
            return JourneyState.deadheadDuring(false);
          }
        }
      }
    } else {
      if (isLayoverStopped && obs.isAtTerminal())
        return JourneyState.layoverBefore();
      else
        return JourneyState.deadheadBefore(null);
    }
  }

  static public boolean isLocationOnATrip(BlockState blockState) {
    final double distanceAlong = blockState.getBlockLocation().getDistanceAlongBlock();
    final BlockTripEntry trip = blockState.getBlockLocation().getActiveTrip();
    final double tripDistFrom = trip.getDistanceAlongBlock();
    final double tripDistTo = tripDistFrom
        + trip.getTrip().getTotalTripDistance();

    if (tripDistFrom < distanceAlong && distanceAlong <= tripDistTo)
      return true;
    else
      return false;
  }

  static public boolean isLocationActive(BlockState blockState) {
    final double distanceAlong = blockState.getBlockLocation().getDistanceAlongBlock();

    if (0.0 < distanceAlong
        && distanceAlong < blockState.getBlockInstance().getBlock().getTotalBlockDistance())
      return true;
    else
      return false;
  }
}
