// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.snapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Builds immutable {@link ConfigSnapshot}s by querying the {@link ResourceCache}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotBuilder {

  static final int SCHEMA_VERSION = 1;

  private final ResourceCache cache;
  private final Clock clock;

  public ConfigSnapshot buildForZone(String zone) {
    log.debug("Building snapshot for zone {}", zone);
    return ConfigSnapshot.builder()
        .schemaVersion(SCHEMA_VERSION)
        .snapshotId(UUID.randomUUID().toString())
        .generatedAt(Instant.now(clock))
        .zone(zone)
        .zoneSpec(cache.zoneByName(zone))
        .routes(cache.routesForZone(zone))
        .consumers(cache.consumersForZone(zone))
        .credentials(cache.credentialsForZone(zone))
        .meshPeers(cache.meshPeersForZone(zone))
        .policies(cache.policiesForZone(zone))
        .build();
  }

  public List<ConfigSnapshot> buildAll() {
    return cache.knownZones().stream().map(this::buildForZone).toList();
  }
}
