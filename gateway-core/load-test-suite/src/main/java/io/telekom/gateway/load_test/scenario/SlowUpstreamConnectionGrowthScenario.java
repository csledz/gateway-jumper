// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.scenario;

import io.telekom.gateway.load_test.driver.GatewayMetricsSampler;
import io.telekom.gateway.load_test.driver.LoadDriver;
import io.telekom.gateway.load_test.driver.LoadProfile;
import io.telekom.gateway.load_test.driver.LoadReport;
import io.telekom.gateway.load_test.upstream.SlowUpstream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * The flagship scenario: a slow upstream whose mean latency climbs from 10ms to 2000ms across the
 * run, while the driver keeps hammering at a fixed RPS. As upstream latency grows, the gateway
 * needs to hold more sockets open (Little's Law: concurrency = arrival_rate * latency), so
 * open-connections should rise monotonically - until the gateway's connection pool saturates, at
 * which point we expect pool-acquire waits and eventually 5xx.
 *
 * <p>Observations produced:
 *
 * <ul>
 *   <li>{@code openConnectionsCorrelation} - Pearson r between the upstream-latency ramp and the
 *       sampled open-connections series. Scenarios assert this is strongly positive.
 *   <li>{@code poolSaturated} - {@code true} if the pending-acquire counter was ever non-zero.
 *   <li>{@code openConnectionsTrajectory} - the full sampled series for the scenario report.
 * </ul>
 */
@Slf4j
public final class SlowUpstreamConnectionGrowthScenario implements Scenario {

  private final SlowUpstream upstream;
  private final int rps;
  private final int durationSeconds;
  private final int concurrency;
  private final long minLatencyMs;
  private final long maxLatencyMs;

  public SlowUpstreamConnectionGrowthScenario(
      SlowUpstream upstream, int rps, int durationSeconds, int concurrency) {
    this(upstream, rps, durationSeconds, concurrency, 10L, 2_000L);
  }

  public SlowUpstreamConnectionGrowthScenario(
      SlowUpstream upstream,
      int rps,
      int durationSeconds,
      int concurrency,
      long minLatencyMs,
      long maxLatencyMs) {
    this.upstream = upstream;
    this.rps = rps;
    this.durationSeconds = durationSeconds;
    this.concurrency = concurrency;
    this.minLatencyMs = minLatencyMs;
    this.maxLatencyMs = maxLatencyMs;
  }

  @Override
  public String name() {
    return "slow-upstream-connection-growth";
  }

  @Override
  public ScenarioResult run(LoadDriver driver, GatewayMetricsSampler sampler) {
    upstream.setLatency(SlowUpstream.LatencyDistribution.CONSTANT, Duration.ofMillis(minLatencyMs));

    // Ramp the upstream latency on its own scheduler while the driver is running.
    AtomicBoolean stop = new AtomicBoolean(false);
    Thread ramp =
        new Thread(
            () -> {
              long startNanos = System.nanoTime();
              long totalNanos = Duration.ofSeconds(durationSeconds).toNanos();
              while (!stop.get()) {
                long elapsed = System.nanoTime() - startNanos;
                double frac = Math.min(1.0, (double) elapsed / totalNanos);
                long latencyMs = minLatencyMs + Math.round((maxLatencyMs - minLatencyMs) * frac);
                upstream.setLatency(
                    SlowUpstream.LatencyDistribution.CONSTANT, Duration.ofMillis(latencyMs));
                try {
                  Thread.sleep(250);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            "slow-upstream-latency-ramp");
    ramp.setDaemon(true);
    ramp.start();

    // Clamp the analyzed sample window to the emission phase. Samples collected during the
    // driver's post-emission drain would muddy the correlation: active-connections dips as
    // traffic stops, which anti-correlates with the climbing latency ramp.
    int samplesBefore = sampler.activeConnections().size();

    LoadReport report;
    try {
      report = driver.run(LoadProfile.steady(rps, durationSeconds, concurrency));
    } finally {
      stop.set(true);
      try {
        ramp.join(Duration.ofSeconds(2).toMillis());
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      upstream.setLatency(SlowUpstream.LatencyDistribution.CONSTANT, Duration.ofMillis(1));
    }
    // Samples taken during emission: roughly (durationSeconds * 1000) / samplerPeriodMs, where the
    // sampler's default period is 500 ms. Taking exactly that many after samplesBefore gives us
    // the emission window - the drain phase appends more samples that we then ignore.
    int samplesDuringEmission = Math.max(1, durationSeconds * 1000 / 500);
    List<Long> allOpen = sampler.activeConnections();
    List<Double> allPending = sampler.pendingAcquireMs();
    int from = Math.min(samplesBefore, allOpen.size());
    int to = Math.min(samplesBefore + samplesDuringEmission, allOpen.size());
    if (to <= from) to = Math.min(allOpen.size(), from + 1);
    List<Long> openConns = allOpen.subList(from, to);
    List<Double> pending =
        allPending.subList(Math.min(from, allPending.size()), Math.min(to, allPending.size()));
    // Demand = active + pending. Under Little's Law this rises monotonically with upstream
    // latency at fixed RPS, regardless of whether the outbound pool has already saturated (in
    // which case the growth shows up in `pending` instead of `active`).
    List<Long> demand = combineDemand(openConns, pending);
    double correlation = latencyVsDemandCorrelation(demand);
    boolean poolSaturated = pending.stream().anyMatch(v -> v > 0.0);

    log.info(
        "slow-upstream-connection-growth: samples={}, correlation={}, poolSaturated={},"
            + " status5xx={}",
        demand.size(),
        correlation,
        poolSaturated,
        report.status5xx());

    return new ScenarioResult(
        report,
        Map.of(
            "openConnectionsCorrelation", correlation,
            "poolSaturated", poolSaturated,
            "openConnectionsTrajectory", openConns,
            "demandTrajectory", demand,
            "pendingAcquireTrajectory", pending,
            "status5xx", report.status5xx()));
  }

  /** Element-wise sum of the two series, truncated to the shorter length. */
  static List<Long> combineDemand(List<Long> active, List<Double> pending) {
    int n = Math.min(active.size(), pending.size());
    java.util.List<Long> out = new java.util.ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(active.get(i) + Math.round(pending.get(i)));
    }
    if (active.size() > n) {
      for (int i = n; i < active.size(); i++) out.add(active.get(i));
    }
    return out;
  }

  /**
   * Pearson correlation between the sample index (proxy for elapsed time, which is our latency
   * ramp) and the observed demand (active + pending). Returns {@link Double#NaN} if there aren't
   * enough samples.
   */
  static double latencyVsDemandCorrelation(List<Long> openConns) {
    int n = openConns.size();
    if (n < 4) return Double.NaN;
    double meanX = (n - 1) / 2.0;
    double sumY = 0;
    for (long y : openConns) sumY += y;
    double meanY = sumY / n;
    double num = 0;
    double denX = 0;
    double denY = 0;
    for (int i = 0; i < n; i++) {
      double dx = i - meanX;
      double dy = openConns.get(i) - meanY;
      num += dx * dy;
      denX += dx * dx;
      denY += dy * dy;
    }
    if (denX == 0 || denY == 0) return 0.0;
    return num / Math.sqrt(denX * denY);
  }
}
