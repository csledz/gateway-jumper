// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.telekom.gateway.mesh_federation.model.ZoneHealth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ZoneHealthRegistryTest {

  @Test
  void gaugeReflectsLatestHeartbeat() {
    MeterRegistry meters = new SimpleMeterRegistry();
    ZoneHealthRegistry registry = new ZoneHealthRegistry(meters, Clock.systemUTC());
    registry.accept(new ZoneHealth("zone-a", true, 0, "zone-a"));

    assertThat(gauge(meters, "zone-a")).isEqualTo(1d);

    registry.accept(new ZoneHealth("zone-a", false, 0, "zone-a"));
    assertThat(gauge(meters, "zone-a")).isEqualTo(0d);
  }

  @Test
  void sweepMarksStaleZonesUnhealthy() {
    long now = 10_000L;
    Clock frozen = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC"));
    MeterRegistry meters = new SimpleMeterRegistry();
    ZoneHealthRegistry registry = new ZoneHealthRegistry(meters, frozen);

    registry.accept(new ZoneHealth("zone-a", true, now - 100, "zone-a"));
    registry.accept(new ZoneHealth("zone-b", true, now - 10_000, "zone-b"));

    registry.sweepStaleness(1_000);

    assertThat(registry.isHealthy("zone-a")).isTrue();
    assertThat(registry.isHealthy("zone-b")).isFalse();
    assertThat(gauge(meters, "zone-b")).isEqualTo(0d);
  }

  @Test
  void summaryGaugeCountsHealthy() {
    MeterRegistry meters = new SimpleMeterRegistry();
    ZoneHealthRegistry registry = new ZoneHealthRegistry(meters, Clock.systemUTC());
    registry.accept(new ZoneHealth("zone-a", true, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-b", false, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-c", true, 0, "reporter"));

    assertThat(meters.get("gateway_mesh_zones_healthy_count").gauge().value()).isEqualTo(2d);
  }

  private static double gauge(MeterRegistry meters, String zone) {
    return meters.get("gateway_mesh_zone_healthy").tag("zone", zone).gauge().value();
  }
}
