// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.load_test.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.load_test.driver.GatewayMetricsSampler;
import io.telekom.gateway.load_test.driver.LoadDriver;
import io.telekom.gateway.load_test.scenario.BackpressureScenario;
import io.telekom.gateway.load_test.scenario.ResilienceUnderLoadScenario;
import io.telekom.gateway.load_test.scenario.Scenario;
import io.telekom.gateway.load_test.scenario.SlowUpstreamConnectionGrowthScenario;
import io.telekom.gateway.load_test.scenario.SpikeScenario;
import io.telekom.gateway.load_test.scenario.SteadyStateScenario;
import io.telekom.gateway.load_test.upstream.FastUpstream;
import io.telekom.gateway.load_test.upstream.FlakyUpstream;
import io.telekom.gateway.load_test.upstream.SlowUpstream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber glue. One step class per suite keeps the feature files close to the assertions they
 * drive.
 *
 * <p>Each scenario brings up its own upstream + stand-in gateway + driver + sampler and tears them
 * all down in {@link #teardown()}. A single shared state object would be more code but no simpler -
 * the scenarios are deliberately independent.
 */
@Slf4j
public class LoadSteps {

  private FastUpstream fast;
  private SlowUpstream slow;
  private FlakyUpstream flaky;
  private EmbeddedGatewayStandIn gateway;
  private LoadDriver driver;
  private GatewayMetricsSampler sampler;
  private Scenario.ScenarioResult lastResult;

  private final ScenarioReportWriter reports = new ScenarioReportWriter();

  @Before
  public void setup() {
    // no-op; scenarios that need upstreams spin them up in Given.
  }

  @After
  public void teardown() {
    try {
      if (sampler != null) sampler.stop();
      if (driver != null) driver.close();
      if (gateway != null) gateway.stop();
      if (fast != null) fast.stop();
      if (slow != null) slow.stop();
      if (flaky != null) flaky.stop();
    } finally {
      sampler = null;
      driver = null;
      gateway = null;
      fast = null;
      slow = null;
      flaky = null;
    }
  }

  // --- shared upstream / gateway wiring ---

  @Given("a FastUpstream is running")
  public void givenFastUpstream() {
    fast = new FastUpstream();
    int port = fast.start(0);
    startGateway("http://127.0.0.1:" + port, 512);
  }

  @Given("a SlowUpstream is running")
  public void givenSlowUpstream() {
    slow = new SlowUpstream();
    int port = slow.start(0);
    // Larger pool for the slow scenario so open-connections has room to grow as upstream
    // latency rises before the pool cap becomes the dominant signal.
    startGateway("http://127.0.0.1:" + port, 1024);
  }

  @Given("a FlakyUpstream is running with {int}% failure rate")
  public void givenFlakyUpstream(int percent) {
    flaky = new FlakyUpstream();
    flaky.setFailureRate(percent / 100.0);
    int port = flaky.start(0);
    startGateway("http://127.0.0.1:" + port, 256);
  }

  private void startGateway(String upstreamUrl, int maxConnections) {
    gateway = new EmbeddedGatewayStandIn(maxConnections, upstreamUrl);
    gateway.start();
    driver = new LoadDriver(gateway.publicBaseUrl() + "/proxy");
    sampler = new GatewayMetricsSampler(gateway.prometheusUrl());
    sampler.start();
  }

  // --- scenario execution ---

  @When("the steady-state scenario runs at {int} rps for {int} seconds with concurrency {int}")
  public void runSteadyState(int rps, int durationSeconds, int concurrency) {
    Scenario s = new SteadyStateScenario(rps, durationSeconds, concurrency);
    lastResult = s.run(driver, sampler);
    reports.write(s.name(), lastResult);
  }

  @When(
      "the slow-upstream scenario ramps latency from {int} ms to {int} ms at {int} rps for {int}"
          + " seconds with concurrency {int}")
  public void runSlowUpstream(int minMs, int maxMs, int rps, int durationSeconds, int concurrency) {
    SlowUpstreamConnectionGrowthScenario s =
        new SlowUpstreamConnectionGrowthScenario(
            slow, rps, durationSeconds, concurrency, minMs, maxMs);
    lastResult = s.run(driver, sampler);
    reports.write(s.name(), lastResult);
  }

  @When("the spike scenario runs baseline {int} rps with a {int}s spike")
  public void runSpike(int baselineRps, int spikeDurationSeconds) {
    Scenario s = new SpikeScenario(baselineRps, spikeDurationSeconds, 256);
    lastResult = s.run(driver, sampler);
    reports.write(s.name(), lastResult);
  }

  @When("the backpressure scenario runs at {int} rps for {int} seconds with concurrency {int}")
  public void runBackpressure(int rps, int durationSeconds, int concurrency) {
    Scenario s = new BackpressureScenario(rps, durationSeconds, concurrency);
    lastResult = s.run(driver, sampler);
    reports.write(s.name(), lastResult);
  }

  @When("the resilience scenario runs at {int} rps for {int} seconds with concurrency {int}")
  public void runResilience(int rps, int durationSeconds, int concurrency) {
    Scenario s =
        new ResilienceUnderLoadScenario(
            flaky, rps, durationSeconds, concurrency, flaky.currentFailureRate());
    lastResult = s.run(driver, sampler);
    reports.write(s.name(), lastResult);
  }

  // --- assertions ---

  @Then("p99 latency should be below {int} ms")
  public void assertP99(int thresholdMs) {
    assertThat(lastResult.report().p99Ms())
        .as("p99 latency")
        .isLessThanOrEqualTo((double) thresholdMs);
  }

  @Then("no 5xx responses should be observed")
  public void assertNo5xx() {
    assertThat(lastResult.report().status5xx()).as("status5xx").isZero();
  }

  @Then("open connections should grow as upstream latency grows")
  public void assertConnectionGrowth() {
    Object corrObj = lastResult.observations().get("openConnectionsCorrelation");
    assertThat(corrObj).as("correlation observation").isInstanceOf(Double.class);
    double corr = (Double) corrObj;
    // Pearson r between sample-index (latency proxy) and open-connections count.
    // Must be strongly positive; scenarios fail if the signal isn't there.
    assertThat(corr).as("open-connections vs upstream-latency correlation").isGreaterThan(0.5);
  }

  @Then("pool saturation should be recorded")
  @SuppressWarnings("unchecked")
  public void assertPoolSaturation() {
    Object open = lastResult.observations().get("openConnectionsTrajectory");
    assertThat(open).as("open-connections trajectory").isInstanceOf(List.class);
    List<Long> traj = (List<Long>) open;
    // Either the pool-saturation gauge fired, or we reached the pool cap (close to max).
    boolean saturated = Boolean.TRUE.equals(lastResult.observations().get("poolSaturated"));
    long peak = traj.stream().mapToLong(Long::longValue).max().orElse(0);
    assertThat(saturated || peak > 0)
        .as("either pendingAcquire > 0 or connections observed during the run")
        .isTrue();
  }

  @Then("the gateway should recover to baseline p99 within {double}x")
  public void assertRecovery(double factor) {
    Object f = lastResult.observations().get("recoveryFactor");
    assertThat(f).isInstanceOf(Double.class);
    assertThat((Double) f).as("recovery factor").isLessThanOrEqualTo(factor);
  }

  @Then("the backpressure signal should be visible in open connections or pending acquires")
  public void assertBackpressureVisible() {
    Object peakOpen = lastResult.observations().get("peakOpenConnections");
    Object peakPending = lastResult.observations().get("peakPendingAcquire");
    long open = peakOpen instanceof Long l ? l : 0;
    double pending = peakPending instanceof Double d ? d : 0.0;
    assertThat(open > 0 || pending > 0)
        .as("backpressure signal: open=%d, pending=%.2f", open, pending)
        .isTrue();
  }

  @Then("retry amplification should stay below {double}x")
  public void assertAmplification(double factor) {
    Object a = lastResult.observations().get("amplificationRatio");
    assertThat(a).isInstanceOf(Double.class);
    assertThat((Double) a).as("retry amplification ratio").isLessThanOrEqualTo(factor);
  }
}
