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

/**
 * CRD modelling a single API route exposed by the gateway. Spec is intentionally minimal; richer
 * fields can be added as the api-crds contract evolves.
 */
@Group("gateway.telekom.io")
@Version("v1")
@Kind("GatewayRoute")
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GatewayRoute extends GatewayResource<GatewayRoute.Spec> {

  @Data
  @NoArgsConstructor
  public static class Spec {
    private String path;
    private String method;
    private String upstreamUrl;
    private String consumerRef;
    private String realm;
    private List<String> policies;
    private Integer timeoutMs;
  }
}
