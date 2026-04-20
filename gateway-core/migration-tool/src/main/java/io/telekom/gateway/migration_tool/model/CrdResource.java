// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic gateway-core CRD envelope used by {@code CrdYamlWriter}.
 *
 * <p>We stay deliberately schema-free here: the mapper fills {@link #spec} with the fields
 * documented for each kind and {@link #metadata} holds at least a {@code name}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrdResource {

  /** Fully qualified API group / version, e.g. {@code gateway.telekom.de/v1alpha1}. */
  private String apiVersion;

  /** CRD kind, e.g. {@code GatewayRoute}. */
  private String kind;

  /** Kubernetes object metadata. Always contains at least {@code name}. */
  private Map<String, Object> metadata = new LinkedHashMap<>();

  /** Arbitrary spec body, shape depends on {@link #kind}. */
  private Map<String, Object> spec = new LinkedHashMap<>();

  /**
   * For {@code kind: Secret} only: the {@code stringData} block (k8s auto-base64-encodes values on
   * apply). Null for CRDs that use {@link #spec}.
   */
  private Map<String, Object> stringData;

  public static CrdResource of(String apiVersion, String kind, String name) {
    CrdResource r = new CrdResource();
    r.setApiVersion(apiVersion);
    r.setKind(kind);
    r.getMetadata().put("name", name);
    return r;
  }

  public String name() {
    Object n = metadata == null ? null : metadata.get("name");
    return n == null ? null : n.toString();
  }
}
