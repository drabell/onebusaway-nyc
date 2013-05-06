package org.onebusaway.nyc.vehicle_tracking.opentrackingtools.impl;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.JourneyStateTransitionModel;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.Observation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockStateObservation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState;
import org.onebusaway.nyc.vehicle_tracking.opentrackingtools.impl.NycTrackingGraph.BlockTripEntryAndDate;
import org.onebusaway.nyc.vehicle_tracking.opentrackingtools.impl.NycTrackingGraph.TripInfo;
import org.onebusaway.realtime.api.EVehiclePhase;
import org.onebusaway.transit_data_federation.services.blocks.ScheduledBlockLocation;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockTripEntry;

import gov.sandia.cognition.learning.algorithm.IncrementalLearner;
import gov.sandia.cognition.math.LogMath;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.BayesianEstimatorPredictor;
import gov.sandia.cognition.util.AbstractCloneableSerializable;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import org.opentrackingtools.distributions.CountedDataDistribution;
import org.opentrackingtools.distributions.DeterministicDataDistribution;
import org.opentrackingtools.distributions.PathStateDistribution;
import org.opentrackingtools.estimators.MotionStateEstimatorPredictor;
import org.opentrackingtools.paths.PathEdge;
import org.opentrackingtools.util.model.MutableDoubleCount;

import umontreal.iro.lecuyer.probdist.FoldedNormalDist;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

/**
 * An estimator/predictor that allows for predictive sampling of a run/block
 * state. The current implementation simply returns a discrete distribution over
 * the possible runs, with densities determined by their likelihood. Basically,
 * we have a deterministic prior.
 * 
 * @author bwillard
 * 
 */
public class RunStateEstimator extends AbstractCloneableSerializable implements
    IncrementalLearner<PathStateDistribution, DataDistribution<RunState>>,
    BayesianEstimatorPredictor<RunState, RunState, DataDistribution<RunState>> {

  private static final long serialVersionUID = -1461026886038720233L;

  private final NycTrackingGraph nycGraph;
  private final NycVehicleStateDistribution nycVehicleStateDist;
  private final Observation obs;
  private static final long _tripSearchTimeAfterLastStop = 5 * 60 * 60 * 1000;
  private static final long _tripSearchTimeBeforeFirstStop = 5 * 60 * 60 * 1000;

  public static long getTripSearchTimeAfterLastStop() {
    return _tripSearchTimeAfterLastStop;
  }

  public static long getTripSearchTimeBeforeFirstStop() {
    return _tripSearchTimeBeforeFirstStop;
  }

  public RunStateEstimator(NycTrackingGraph graph, Observation obs,
      NycVehicleStateDistribution nycVehicleStateDist,
      VehicleState prevOldTypeVehicleState, Random rng) {
    this.obs = obs;
    this.nycGraph = graph;
    this.nycVehicleStateDist = nycVehicleStateDist;
  }

  @Override
  public CountedDataDistribution<RunState> createPredictiveDistribution(
      DataDistribution<RunState> posterior) {
    return createInitialLearnedObject();
  }

  @Override
  public CountedDataDistribution<RunState> createInitialLearnedObject() {

    final long time = obs.getTimestamp().getTime();
    final Date timeFrom = new Date(time - _tripSearchTimeAfterLastStop);
    final Date timeTo = new Date(time + _tripSearchTimeBeforeFirstStop);

    final PathEdge pathEdge = nycVehicleStateDist.getPathStateParam().getParameterPrior().getPathState().getEdge();
    final TripInfo tripInfo = nycGraph.getTripInfo(pathEdge.getInferenceGraphEdge());
    final double likelihoodHasNotMoved = likelihoodOfNotMovedState(nycVehicleStateDist.getPathStateParam().getParameterPrior());
    double nonNullTotalLikelihood = Double.NEGATIVE_INFINITY;

    final Map<RunState, MutableDoubleCount> resultDist = Maps.newIdentityHashMap();

    final RunState currentRunState = nycVehicleStateDist.getRunStateParam() != null
        ? nycVehicleStateDist.getRunStateParam().getValue() : null;
    final VehicleState currentOldTypeVehicleState = currentRunState != null
        ? currentRunState.getVehicleState() : null;

    if (tripInfo != null) {

      /*
       * In this case our previous state was snapped to particular road segment
       * for run/block set, so find all the active ones in our time window,
       * produce states for them, and weigh.
       */
      final Collection<BlockTripEntryAndDate> activeEntries = tripInfo.getActiveTrips(
          timeFrom.getTime(), timeTo.getTime());
      for (final BlockTripEntryAndDate blockTripEntryAndDate : activeEntries) {

        final BlockTripEntry blockTripEntry = blockTripEntryAndDate.getBlockTripEntry();
        final long serviceDate = blockTripEntryAndDate.getServiceDate().getTime();

        final BlockStateObservation blockStateObs = this.nycGraph.getBlockStateObs(
            obs,
            nycVehicleStateDist.getPathStateParam().getParameterPrior().getPathState(),
            blockTripEntry, serviceDate);

        /*
         * DEBUG check that the shape/edge geom has a valid mapping
         */
        final AgencyAndId shapeId = blockTripEntry.getTrip().getShapeId();
        Preconditions.checkState(this.nycGraph.getLengthsAlongShapeMap().contains(
            shapeId, pathEdge.getInferenceGraphEdge().getGeometry()));

        final RunState runStateMoved = new RunState(nycGraph, obs,
            nycVehicleStateDist, blockStateObs, false,
            currentOldTypeVehicleState, false);

        final RunState.RunStateEdgePredictiveResults mtaEdgeResultsMoved = runStateMoved.computeAnnotatedLogLikelihood();
        runStateMoved.setLikelihoodInfo(mtaEdgeResultsMoved);
        mtaEdgeResultsMoved.setMovedLogLikelihood(Math.log(1d - likelihoodHasNotMoved));
        if (mtaEdgeResultsMoved.getTotalLogLik() > Double.NEGATIVE_INFINITY) {
          nonNullTotalLikelihood = LogMath.add(nonNullTotalLikelihood,
              mtaEdgeResultsMoved.schedLogLikelihood);
          resultDist.put(runStateMoved, new MutableDoubleCount(
              mtaEdgeResultsMoved.getTotalLogLik(), 1));
        }

        final RunState runStateNotMoved = new RunState(nycGraph, obs,
            nycVehicleStateDist, blockStateObs, true,
            currentOldTypeVehicleState, false);

        final RunState.RunStateEdgePredictiveResults mtaEdgeResultsNotMoved = runStateNotMoved.computeAnnotatedLogLikelihood();
        runStateNotMoved.setLikelihoodInfo(mtaEdgeResultsNotMoved);
        mtaEdgeResultsNotMoved.setMovedLogLikelihood(Math.log(likelihoodHasNotMoved));
        if (mtaEdgeResultsNotMoved.getTotalLogLik() > Double.NEGATIVE_INFINITY) {
          nonNullTotalLikelihood = LogMath.add(nonNullTotalLikelihood,
              mtaEdgeResultsNotMoved.schedLogLikelihood);
          resultDist.put(runStateNotMoved, new MutableDoubleCount(
              mtaEdgeResultsNotMoved.getTotalLogLik(), 1));
        }
      }
    }

    /*
     * Our state wasn't on a road corresponding to a run/block set, or it wasn't
     * on a road at all. We add null to ensure a run/block-less possibility.
     */
    final Collection<BlockStateObservation> blockStates = nycGraph.getBlockStatesFromObservation(obs);
    blockStates.add(null);

    /*
     * Now add the state that simply moves forward along the block distance
     * according to the projected distance moved.
     */
    if (currentRunState != null) {
      if (currentRunState.getBlockStateObs() != null) {
        final double distProjected = MotionStateEstimatorPredictor.Og.times(
            nycVehicleStateDist.getPathStateParam().getParameterPrior().getGroundDistribution().getMean().minus(
                nycVehicleStateDist.getParentState().getPathStateParam().getParameterPrior().getGroundDistribution().getMean())).norm2();
        blockStates.add(nycGraph.getBlocksFromObservationService().getBlockStateObservationFromDist(
            this.obs,
            currentRunState.getBlockStateObs().getBlockState().getBlockInstance(),
            currentRunState.getBlockStateObs().getBlockState().getBlockLocation().getDistanceAlongBlock()
                + distProjected));
      }
    }

    for (final BlockStateObservation blockStateObs : blockStates) {

      /*
       * Also, above we handled "snapped" states, so ignore them if they show up
       * again here.
       */
      final boolean isInService = blockStateObs != null
          && JourneyStateTransitionModel.isLocationOnATrip(blockStateObs.getBlockState());
      if (blockStateObs != null
          && isInService
          && tripInfo != null
          && tripInfo.getShapeIds().contains(
              blockStateObs.getBlockState().getRunTripEntry().getTripEntry().getShapeId())) {
        continue;
      }

      final boolean movedIsDetoured = blockStateObs != null
          ? JourneyStateTransitionModel.isDetour(blockStateObs, null, true,
              currentOldTypeVehicleState) : false;

      /*
       * Only consider in-progress states when we've determined they're detours.
       */
      if (!isInService || movedIsDetoured) {

        final RunState runStateMoved = new RunState(nycGraph, obs,
            nycVehicleStateDist, blockStateObs, false,
            currentOldTypeVehicleState, movedIsDetoured);

        Preconditions.checkState(runStateMoved.getJourneyState().getPhase() != EVehiclePhase.IN_PROGRESS);

        final RunState.RunStateEdgePredictiveResults mtaEdgeResultsMoved = runStateMoved.computeAnnotatedLogLikelihood();
        runStateMoved.setLikelihoodInfo(mtaEdgeResultsMoved);
        mtaEdgeResultsMoved.setMovedLogLikelihood(Math.log(1d - likelihoodHasNotMoved));
        if (mtaEdgeResultsMoved.getTotalLogLik() > Double.NEGATIVE_INFINITY) {
          if (blockStateObs != null)
            nonNullTotalLikelihood = LogMath.add(nonNullTotalLikelihood,
                mtaEdgeResultsMoved.schedLogLikelihood);
          resultDist.put(runStateMoved, new MutableDoubleCount(
              mtaEdgeResultsMoved.getTotalLogLik(), 1));
        }
      }

      final boolean notMovedIsDetoured = blockStateObs != null
          ? JourneyStateTransitionModel.isDetour(blockStateObs, null, false,
              currentOldTypeVehicleState) : false;

      if (!isInService || notMovedIsDetoured) {
        final RunState runStateNotMoved = new RunState(nycGraph, obs,
            nycVehicleStateDist, blockStateObs, true,
            currentOldTypeVehicleState, notMovedIsDetoured);

        final RunState.RunStateEdgePredictiveResults mtaEdgeResultsNotMoved = runStateNotMoved.computeAnnotatedLogLikelihood();
        runStateNotMoved.setLikelihoodInfo(mtaEdgeResultsNotMoved);
        mtaEdgeResultsNotMoved.setMovedLogLikelihood(Math.log(likelihoodHasNotMoved));
        if (mtaEdgeResultsNotMoved.getTotalLogLik() > Double.NEGATIVE_INFINITY) {
          if (blockStateObs != null)
            nonNullTotalLikelihood = LogMath.add(nonNullTotalLikelihood,
                mtaEdgeResultsNotMoved.schedLogLikelihood);
          resultDist.put(runStateNotMoved, new MutableDoubleCount(
              mtaEdgeResultsNotMoved.getTotalLogLik(), 1));
        }
      }
    }

    /*
     * Now, normalize the non-null block states, so that comparisons with null
     * block states will be valid.
     */
    final CountedDataDistribution<RunState> result = new CountedDataDistribution<RunState>(
        true);
    for (final Entry<RunState, MutableDoubleCount> entry : resultDist.entrySet()) {
      if (entry.getKey().getBlockStateObs() != null) {
        final double newValue = entry.getValue().doubleValue()
            - nonNullTotalLikelihood;
        result.increment(entry.getKey(), newValue, entry.getValue().count);
      } else {
        result.increment(entry.getKey(), entry.getValue().doubleValue(),
            entry.getValue().count);
      }
    }

    Preconditions.checkState(!result.isEmpty());
    return result;
  }

  @Override
  public void update(DataDistribution<RunState> priorPredRunStateDist,
      PathStateDistribution posteriorPathStateDist) {

    Preconditions.checkArgument(priorPredRunStateDist instanceof DeterministicDataDistribution<?>);

    final DeterministicDataDistribution<RunState> priorRunDist = (DeterministicDataDistribution<RunState>) priorPredRunStateDist;

    final RunState priorPredRunState = priorPredRunStateDist.getMaxValueKey();
    /*
     * We must update update the run state, since the path belief gets updated.
     */
    final RunState posteriorRunState = priorPredRunState.clone();
    /*
     * The update might've changed our estimated motion status. Also, since
     * we're using a MAP-like final estimate, go with the max likelihood.
     */
    final double likelihoodHasNotMoved = likelihoodOfNotMovedState(posteriorPathStateDist);
    if (likelihoodHasNotMoved > 0.5d) {
      posteriorRunState.setVehicleHasNotMoved(true);
    } else {
      posteriorRunState.setVehicleHasNotMoved(false);
    }

    if (posteriorRunState.getBlockStateObs() != null) {

      final boolean predictedInProgress = posteriorRunState.getJourneyState().getPhase() == EVehiclePhase.IN_PROGRESS;
      if (predictedInProgress
          && !posteriorRunState.getJourneyState().getIsDetour()) {
        final ScheduledBlockLocation priorSchedLoc = posteriorRunState.getBlockStateObs().getBlockState().getBlockLocation();

        final BlockStateObservation newBlockStateObs = nycGraph.getBlockStateObs(
            obs,
            posteriorPathStateDist.getPathState(),
            priorSchedLoc.getActiveTrip(),
            posteriorRunState.getBlockStateObs().getBlockState().getBlockInstance().getServiceDate());

        posteriorRunState.setBlockStateObs(newBlockStateObs);
      } else {
        // if (predictedInProgress) {
        // /*
        // * TODO should we propagate states? how?
        // */
        //
        // } else {
        // /*
        // * TODO should we propagate states? how?
        // */
        // }
      }
    }
    priorRunDist.setElement(posteriorRunState);
  }

  /**
   * Sample a state of moved/not-moved using a belief's velocity distribution. <br>
   * This is done by evaluating the complement of a CDF from a folded normal,
   * with mean 0 and a variance from the Frobenius norm of the belief's velocity
   * covariance, at the belief's velocity mean
   * 
   * @param pathStateBelief
   * @return
   */
  public double likelihoodOfNotMovedState(PathStateDistribution pathStateBelief) {
    final double velocityAvg = MotionStateEstimatorPredictor.getVg().times(
        pathStateBelief.getGroundDistribution().getMean()).norm2();
    final double velocityVar = MotionStateEstimatorPredictor.getVg().times(
        pathStateBelief.getGroundDistribution().getCovariance()).times(
        MotionStateEstimatorPredictor.getVg().transpose()).normFrobenius();

    final double likelihood = Math.min(
        1d,
        Math.max(0d,
            1d - FoldedNormalDist.cdf(0d, Math.sqrt(velocityVar), velocityAvg)));

    return likelihood;
  }

  @Override
  public DataDistribution<RunState> learn(Collection<? extends RunState> data) {
    return null;
  }

  @Override
  public void update(DataDistribution<RunState> target,
      Iterable<? extends PathStateDistribution> data) {
  }

}