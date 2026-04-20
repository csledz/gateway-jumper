// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.scenario;

import io.telekom.gateway.load_test.driver.GatewayMetricsSampler;
import io.telekom.gateway.load_test.driver.LoadDriver;
import io.telekom.gateway.load_test.driver.LoadReport;

/**
 * A self-contained load scenario. Implementations set up the upstreams they need, drive traffic via
 * the supplied {@link LoadDriver}, sample gateway metrics via the supplied {@link
 * GatewayMetricsSampler}, and return a structured {@link ScenarioResult} the caller can assert
 * against.
 *
 * <p>Scenarios are idempotent and safe to run back-to-back: they must reset their own upstream
 * state (latency distribution, failure rate) before returning.
 */
public interface Scenario {

  /** Human-readable scenario id used in reports. */
  String name();

  /**
   * Runs the scenario.
   *
   * @param driver driver aimed at the gateway's public endpoint
   * @param sampler sampler already polling the gateway's /actuator/prometheus
   * @return the load report plus scenario-specific observations
   */
  ScenarioResult run(LoadDriver driver, GatewayMetricsSampler sampler);

  /**
   * Scenario output: the raw {@link LoadReport} plus a free-form observations map used for
   * scenario-specific assertions (e.g. {@code openConnectionsCorrelation} for the slow-upstream
   * scenario).
   */
  record ScenarioResult(LoadReport report, java.util.Map<String, Object> observations) {}
}
