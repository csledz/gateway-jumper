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

/** CRD modelling a credential (secret ref or public key) attached to a consumer. */
@Group("gateway.telekom.io")
@Version("v1")
@Kind("GatewayCredential")
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GatewayCredential extends GatewayResource<GatewayCredential.Spec> {

  @Data
  @NoArgsConstructor
  public static class Spec {
    private String consumerRef;

    /** e.g. client_secret, jwt_public_key, mtls_cert */
    private String type;

    private String secretRef;
    private String publicKeyPem;
  }
}
