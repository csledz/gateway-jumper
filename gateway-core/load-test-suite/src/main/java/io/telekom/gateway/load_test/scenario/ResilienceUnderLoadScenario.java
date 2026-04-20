// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.scenario;

import io.telekom.gateway.load_test.driver.GatewayMetricsSampler;
import io.telekom.gateway.load_test.driver.LoadDriver;
import io.telekom.gateway.load_test.driver.LoadProfile;
import io.telekom.gateway.load_test.driver.LoadReport;
import io.telekom.gateway.load_test.upstream.FlakyUpstream;
import java.util.Map;

/**
 * Drives moderate RPS against the gateway while the upstream ({@link FlakyUpstream}) returns 5xx on
 * 20% of requests. We expect the gateway's retry and/or circuit breaker to smooth the visible
 * failure rate - ideally we see a client-observed 5xx rate substantially below 20%, and we assert
 * that retries haven't caused an *amplified* attempt count on the upstream (i.e. the ratio of
 * upstream requests to driver requests is less than the retry budget).
 *
 * <p>Observations:
 *
 * <ul>
 *   <li>{@code upstreamTotal}, {@code upstreamFailures} - what the upstream saw.
 *   <li>{@code clientStatus5xx}, {@code clientStatus2xx} - what the driver saw through the gateway.
 *   <li>{@code amplificationRatio} = upstreamTotal / driverTotal. Amplification &gt; 3 is a red
 *       flag.
 * </ul>
 */
public final class ResilienceUnderLoadScenario implements Scenario {

  private final FlakyUpstream upstream;
  private final int rps;
  private final int durationSeconds;
  private final int concurrency;
  private final double failureFraction;

  public ResilienceUnderLoadScenario(
      FlakyUpstream upstream,
      int rps,
      int durationSeconds,
      int concurrency,
      double failureFraction) {
    this.upstream = upstream;
    this.rps = rps;
    this.durationSeconds = durationSeconds;
    this.concurrency = concurrency;
    this.failureFraction = failureFraction;
  }

  @Override
  public String name() {
    return "resilience-under-load";
  }

  @Override
  public ScenarioResult run(LoadDriver driver, GatewayMetricsSampler sampler) {
    double previous = upstream.currentFailureRate();
    upstream.setFailureRate(failureFraction);
    long upstreamBefore = upstream.getTotal().get();
    long failsBefore = upstream.getFailures().get();
    LoadReport r;
    try {
      r = driver.run(LoadProfile.steady(rps, durationSeconds, concurrency));
    } finally {
      upstream.setFailureRate(previous);
    }
    long upstreamTotal = upstream.getTotal().get() - upstreamBefore;
    long upstreamFails = upstream.getFailures().get() - failsBefore;
    double amplification = r.totalRequests() == 0 ? 0 : (double) upstreamTotal / r.totalRequests();
    return new ScenarioResult(
        r,
        Map.of(
            "upstreamTotal", upstreamTotal,
            "upstreamFailures", upstreamFails,
            "clientStatus5xx", r.status5xx(),
            "clientStatus2xx", r.status2xx(),
            "amplificationRatio", amplification));
  }
}
