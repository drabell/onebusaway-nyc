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
package org.onebusaway.nyc.integration_tests.vehicle_tracking_webapp.cases;

import org.onebusaway.realtime.api.EVehiclePhase;

import org.junit.Ignore;

@Ignore
public class Trace_2782_20111202_211038_222038_IntegrationTest extends AbstractTraceRunner {

  public Trace_2782_20111202_211038_222038_IntegrationTest() throws Exception {
    super("2782_20111202_211038_222038.csv.gz");
    setBundle("si", "2011-12-07T00:00:00EDT");
    setMinAccuracyRatioForPhase(EVehiclePhase.DEADHEAD_BEFORE, 0.9);
    setMinAccuracyRatioForPhase(EVehiclePhase.IN_PROGRESS, 0.85);
  }
}
