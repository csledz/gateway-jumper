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
 * Drives traffic at an RPS known to exceed the gateway's processing rate and measures queue-depth
 * growth through the {@link GatewayMetricsSampler}'s pending-acquire and open-connections series.
 *
 * <p>The useful signal here is that p99 diverges from baseline and pending-acquire grows - together
 * that means backpressure is being felt. Reported observations:
 *
 * <ul>
 *   <li>{@code p99Ms}, {@code status5xx}, {@code totalRequests} from the load report.
 *   <li>{@code peakOpenConnections}, {@code peakPendingAcquire} from the sampler.
 * </ul>
 */
public final class BackpressureScenario implements Scenario {

  private final int overdriveRps;
  private final int durationSeconds;
  private final int concurrency;

  public BackpressureScenario(int overdriveRps, int durationSeconds, int concurrency) {
    this.overdriveRps = overdriveRps;
    this.durationSeconds = durationSeconds;
    this.concurrency = concurrency;
  }

  @Override
  public String name() {
    return "backpressure";
  }

  @Override
  public ScenarioResult run(LoadDriver driver, GatewayMetricsSampler sampler) {
    LoadReport r = driver.run(LoadProfile.steady(overdriveRps, durationSeconds, concurrency));
    long peakOpen = sampler.activeConnections().stream().mapToLong(Long::longValue).max().orElse(0);
    double peakPending =
        sampler.pendingAcquireMs().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    return new ScenarioResult(
        r,
        Map.of(
            "p99Ms", r.p99Ms(),
            "status5xx", r.status5xx(),
            "totalRequests", r.totalRequests(),
            "peakOpenConnections", peakOpen,
            "peakPendingAcquire", peakPending));
  }
}
