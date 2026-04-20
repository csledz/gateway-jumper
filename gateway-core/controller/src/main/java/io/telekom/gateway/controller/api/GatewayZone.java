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

/** CRD modelling a gateway zone — a logical grouping of realms sharing one Redis health store. */
@Group("gateway.telekom.io")
@Version("v1")
@Kind("GatewayZone")
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GatewayZone extends GatewayResource<GatewayZone.Spec> {

  @Data
  @NoArgsConstructor
  public static class Spec {
    private String zoneName;
    private String redisUrl;
    private String issuerUrl;
    private Boolean failoverEnabled;
  }
}
