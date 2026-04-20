// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.failover;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.telekom.gateway.mesh_federation.health.ZoneHealthRegistry;
import io.telekom.gateway.mesh_federation.model.ZoneHealth;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FailoverSelectorTest {

  private final ZoneHealthRegistry registry =
      new ZoneHealthRegistry(new SimpleMeterRegistry(), Clock.systemUTC());
  private final FailoverSelector selector = new FailoverSelector(registry);

  @Test
  void picksFirstHealthy() {
    registry.accept(new ZoneHealth("zone-a", false, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-b", true, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-c", true, 0, "reporter"));

    assertThat(selector.select(List.of("zone-a", "zone-b", "zone-c"), Set.of())).contains("zone-b");
  }

  @Test
  void respectsSkipList() {
    registry.accept(new ZoneHealth("zone-a", true, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-b", true, 0, "reporter"));

    assertThat(selector.select(List.of("zone-a", "zone-b"), Set.of("zone-a"))).contains("zone-b");
  }

  @Test
  void emptyWhenAllUnhealthyOrSkipped() {
    registry.accept(new ZoneHealth("zone-a", true, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-b", false, 0, "reporter"));

    assertThat(selector.select(List.of("zone-a", "zone-b"), Set.of("zone-a"))).isEmpty();
  }

  @Test
  void emptyWhenNothingKnown() {
    assertThat(selector.select(List.of("zone-a"), Set.of())).isEmpty();
  }
}
