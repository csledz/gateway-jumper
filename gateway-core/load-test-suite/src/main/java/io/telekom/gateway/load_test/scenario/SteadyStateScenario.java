// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.scenario;

import io.telekom.gateway.load_test.driver.GatewayMetricsSampler;
import io.telekom.gateway.load_test.driver.LoadDriver;
import io.telekom.gateway.load_test.driver.LoadProfile;
import io.telekom.gateway.load_test.driver.LoadReport;
import java.util.Map;

/**
 * Baseline scenario: fixed RPS against a FastUpstream for {@code durationSeconds} seconds.
 *
 * <p>Assertions are left to the caller; the scenario simply surfaces {@code p99} and 5xx count in
 * the observations map so the Cucumber step can compare against the threshold it received from the
 * feature file.
 */
public final class SteadyStateScenario implements Scenario {

  private final int rps;
  private final int durationSeconds;
  private final int concurrency;

  public SteadyStateScenario(int rps, int durationSeconds, int concurrency) {
    this.rps = rps;
    this.durationSeconds = durationSeconds;
    this.concurrency = concurrency;
  }

  @Override
  public String name() {
    return "steady-state";
  }

  @Override
  public ScenarioResult run(LoadDriver driver, GatewayMetricsSampler sampler) {
    LoadProfile profile = LoadProfile.steady(rps, durationSeconds, concurrency);
    LoadReport r = driver.run(profile);
    return new ScenarioResult(
        r,
        Map.of(
            "p99Ms", r.p99Ms(),
            "status5xx", r.status5xx(),
            "totalRequests", r.totalRequests()));
  }
}
