// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.telekom.gateway.controller.api.GatewayConsumer;
import io.telekom.gateway.controller.api.GatewayCredential;
import io.telekom.gateway.controller.api.GatewayMeshPeer;
import io.telekom.gateway.controller.api.GatewayPolicy;
import io.telekom.gateway.controller.api.GatewayRoute;
import io.telekom.gateway.controller.api.GatewayZone;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable, data-plane-facing configuration snapshot for a single zone. Serialised as JSON and
 * POSTed to every registered data-plane pod.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigSnapshot {

  /** snapshot schema version — increment on wire-format changes */
  int schemaVersion;

  /** unique id (uuid) of this snapshot */
  String snapshotId;

  /** time the controller built the snapshot */
  Instant generatedAt;

  /** zone name this snapshot belongs to */
  String zone;

  /** the zone resource itself (single) */
  GatewayZone zoneSpec;

  List<GatewayRoute> routes;
  List<GatewayConsumer> consumers;
  List<GatewayCredential> credentials;
  List<GatewayMeshPeer> meshPeers;
  List<GatewayPolicy> policies;
}
