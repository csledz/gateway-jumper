// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cucumber.java.en.Given;
import io.telekom.gateway.e2e.MeshTopology;
import java.time.Duration;

/** Drives the zone-failover scenario via the Redis zone-health pub/sub. */
public class FailoverSteps {

  private final World world;

  public FailoverSteps(World world) {
    this.world = world;
  }

  @Given("zone {word} is announced UNHEALTHY")
  public void zoneIsUnhealthy(String zone) {
    MeshTopology t = world.topology;
    t.getHealthBus().markUnhealthy(zone);
    // Give the pub/sub a beat to reach every embedded proxy.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(t.getHealthBus().getUnhealthy()).contains(zone));
  }

  @Given("zone {word} is announced HEALTHY")
  public void zoneIsHealthy(String zone) {
    MeshTopology t = world.topology;
    t.getHealthBus().markHealthy(zone);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(t.getHealthBus().getUnhealthy()).doesNotContain(zone));
  }
}
