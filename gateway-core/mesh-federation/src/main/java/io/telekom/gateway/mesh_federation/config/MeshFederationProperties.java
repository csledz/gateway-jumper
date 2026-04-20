// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code gateway.mesh.*} in application configuration. */
@ConfigurationProperties(prefix = "gateway.mesh")
public record MeshFederationProperties(
    String localZone,
    String localRealm,
    int healthIntervalSeconds,
    int staleAfterSeconds,
    String channel) {

  public MeshFederationProperties {
    if (localZone == null || localZone.isBlank()) {
      localZone = "zone-dev";
    }
    if (localRealm == null || localRealm.isBlank()) {
      localRealm = "default";
    }
    if (healthIntervalSeconds <= 0) {
      healthIntervalSeconds = 5;
    }
    if (staleAfterSeconds <= 0) {
      staleAfterSeconds = healthIntervalSeconds * 3;
    }
    if (channel == null || channel.isBlank()) {
      channel = "gateway-zone-status";
    }
  }
}
