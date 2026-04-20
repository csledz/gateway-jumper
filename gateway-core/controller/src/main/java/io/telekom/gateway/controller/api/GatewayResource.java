// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Base class for all gateway CRDs handled by the controller. Uses fabric8's {@link CustomResource}
 * so the kubernetes-client can serialize/deserialize and watch these resources out of the box.
 *
 * <p>NOTE: once the sibling {@code api-crds} PR lands, this base type and its subclasses will be
 * superseded by generated client code.
 */
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class GatewayResource<S> extends CustomResource<S, Void> implements Namespaced {

  public String getZone() {
    ObjectMeta md = getMetadata();
    if (md == null || md.getLabels() == null) {
      return null;
    }
    return md.getLabels().get("gateway.telekom.io/zone");
  }
}
