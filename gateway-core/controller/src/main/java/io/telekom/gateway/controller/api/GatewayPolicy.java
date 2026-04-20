// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.api;

import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** CRD modelling a reusable policy (rate-limit, auth, header rewrite) referenced by routes. */
@Group("gateway.telekom.io")
@Version("v1")
@Kind("GatewayPolicy")
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GatewayPolicy extends GatewayResource<GatewayPolicy.Spec> {

  @Data
  @NoArgsConstructor
  public static class Spec {
    /** e.g. rate-limit, jwt-auth, header-rewrite */
    private String kind;

    private Map<String, String> config;
  }
}
