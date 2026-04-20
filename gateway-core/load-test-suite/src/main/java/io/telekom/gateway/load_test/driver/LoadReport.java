// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.driver;

/**
 * Immutable snapshot of what happened during one load run. All latency fields are in milliseconds.
 *
 * <p>Gateway-side time-series (open connections, pool acquire waits, etc.) live on the {@link
 * GatewayMetricsSampler} that runs alongside the driver; scenarios post-process those directly to
 * look for trends (e.g. monotonic growth). Keeping them off the report avoids stale copies and
 * keeps the driver cleanly decoupled from the sampler.
 */
public record LoadReport(
    double p50Ms,
    double p95Ms,
    double p99Ms,
    double p999Ms,
    double maxMs,
    long errorCount,
    long status2xx,
    long status4xx,
    long status5xx,
    long totalRequests) {

  /** Total responses observed (irrespective of status). */
  public long totalResponses() {
    return status2xx + status4xx + status5xx;
  }
}
