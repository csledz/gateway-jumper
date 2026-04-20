// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.api;

import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** CRD modelling a gateway consumer (caller identity). */
@Group("gateway.telekom.io")
@Version("v1")
@Kind("GatewayConsumer")
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GatewayConsumer extends GatewayResource<GatewayConsumer.Spec> {

  @Data
  @NoArgsConstructor
  public static class Spec {
    private String clientId;
    private String realm;
    private List<String> scopes;
    private List<String> groups;
  }
}
