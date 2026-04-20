// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.api;

import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** CRD modelling a peer gateway zone the local zone can fail over to / forward mesh JWTs to. */
@Group("gateway.telekom.io")
@Version("v1")
@Kind("GatewayMeshPeer")
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GatewayMeshPeer extends GatewayResource<GatewayMeshPeer.Spec> {

  @Data
  @NoArgsConstructor
  public static class Spec {
    private String peerZone;
    private String peerUrl;
    private String sharedSecretRef;
    private Integer priority;
  }
}
