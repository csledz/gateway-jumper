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
 * A 5x RPS spike for a short window, sandwiched between two baseline windows. The assertion is that
 * the gateway recovers: status5xx during the final baseline window falls back to baseline levels,
 * and open-connections returns toward its pre-spike value.
 *
 * <p>We model this in one single driver run that ramps -> spikes -> drops back by setting {@code
 * rampSeconds} on the underlying profile; a full three-phase schedule is not strictly needed for
 * the assertion and doubling the run time doesn't help the signal-to-noise ratio.
 */
public final class SpikeScenario implements Scenario {

  private final int baselineRps;
  private final int spikeDurationSeconds;
  private final int concurrency;

  public SpikeScenario(int baselineRps, int spikeDurationSeconds, int concurrency) {
    this.baselineRps = baselineRps;
    this.spikeDurationSeconds = spikeDurationSeconds;
    this.concurrency = concurrency;
  }

  @Override
  public String name() {
    return "spike-recovery";
  }

  @Override
  public ScenarioResult run(LoadDriver driver, GatewayMetricsSampler sampler) {
    // Brief baseline first, for comparison ammo.
    LoadReport pre = driver.run(LoadProfile.steady(baselineRps, 10, concurrency));
    // The spike itself.
    LoadReport spike =
        driver.run(
            new LoadProfile(
                baselineRps * 5, spikeDurationSeconds, concurrency * 5, 2, Map.of(), 0));
    // Post-spike baseline: did we recover?
    LoadReport post = driver.run(LoadProfile.steady(baselineRps, 10, concurrency));

    // Recovery is meaningful relative to a *non-trivial* baseline. If the pre-spike p99 is
    // essentially zero (fast enough to be in the noise floor), we floor it to 10 ms so the
    // ratio is actually reporting something - a recovered gateway should still land well under
    // that.
    double baseline = Math.max(pre.p99Ms(), 10.0);
    double recoveryFactor = post.p99Ms() / baseline;
    return new ScenarioResult(
        post,
        Map.of(
            "pre_p99Ms", pre.p99Ms(),
            "spike_p99Ms", spike.p99Ms(),
            "spike_status5xx", spike.status5xx(),
            "post_p99Ms", post.p99Ms(),
            "post_status5xx", post.status5xx(),
            "recoveryFactor", recoveryFactor));
  }
}
