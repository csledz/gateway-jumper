// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.failover;

import io.telekom.gateway.mesh_federation.health.ZoneHealthRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure function over {@code (candidateZones, skipZones, registry) -> Optional<String>}.
 *
 * <p>Rules (see README §Failover): first zone in {@code candidateZones} that is (a) not in {@code
 * skipZones} and (b) healthy per the registry wins. Empty Optional ⇒ 503.
 */
public final class FailoverSelector {

  private final ZoneHealthRegistry registry;

  public FailoverSelector(ZoneHealthRegistry registry) {
    this.registry = registry;
  }

  public Optional<String> select(List<String> candidateZones, Set<String> skipZones) {
    for (String zone : candidateZones) {
      if (!skipZones.contains(zone) && registry.isHealthy(zone)) {
        return Optional.of(zone);
      }
    }
    return Optional.empty();
  }
}
