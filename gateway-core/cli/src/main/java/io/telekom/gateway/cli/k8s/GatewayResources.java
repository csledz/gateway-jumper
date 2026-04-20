// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.k8s;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.List;
import java.util.Map;

/**
 * CRD metadata + helpers for the gateway-core custom resources the CLI talks to.
 *
 * <p>Using {@link GenericKubernetesResource} with an explicit {@link ResourceDefinitionContext}
 * keeps the CLI decoupled from concrete CRD model classes and avoids the extra {@code /apis/*}
 * discovery round-trip fabric8 otherwise performs when the kind is only known by name.
 */
public final class GatewayResources {

  private GatewayResources() {}

  /** API group that hosts gateway-core CRDs. */
  public static final String GROUP = "gateway.telekom.io";

  /** Stored API version. */
  public static final String VERSION = "v1alpha1";

  public static final String KIND_ROUTE = "Route";
  public static final String KIND_ZONE = "Zone";
  public static final String KIND_CONSUMER = "Consumer";
  public static final String KIND_MESH_PEER = "MeshPeer";

  public static final String PLURAL_ROUTES = "routes";
  public static final String PLURAL_ZONES = "zones";
  public static final String PLURAL_CONSUMERS = "consumers";
  public static final String PLURAL_MESH_PEERS = "meshpeers";

  private static ResourceDefinitionContext context(String kind, String plural) {
    return new ResourceDefinitionContext.Builder()
        .withGroup(GROUP)
        .withVersion(VERSION)
        .withKind(kind)
        .withPlural(plural)
        .withNamespaced(true)
        .build();
  }

  /** List resources of the given kind. Scoped cluster-wide when {@code namespace} is blank. */
  public static List<GenericKubernetesResource> list(
      KubernetesClient client, String namespace, String kind, String plural) {
    var op = client.genericKubernetesResources(context(kind, plural));
    if (namespace == null || namespace.isBlank()) {
      return op.list().getItems();
    }
    return op.inNamespace(namespace).list().getItems();
  }

  /** Load one named resource. Returns {@code null} if not found. */
  public static GenericKubernetesResource get(
      KubernetesClient client, String namespace, String kind, String plural, String name) {
    return client
        .genericKubernetesResources(context(kind, plural))
        .inNamespace(namespace)
        .withName(name)
        .get();
  }

  /** Extract the {@code metadata.name}. Never {@code null}. */
  public static String name(GenericKubernetesResource r) {
    return r.getMetadata() != null && r.getMetadata().getName() != null
        ? r.getMetadata().getName()
        : "<anonymous>";
  }

  /** Extract {@code spec} fields; returns an empty map when missing. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> spec(GenericKubernetesResource r) {
    Object spec =
        r.getAdditionalProperties() == null ? null : r.getAdditionalProperties().get("spec");
    if (spec instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    return Map.of();
  }

  /** Extract the {@code status} block; returns empty map when missing. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> status(GenericKubernetesResource r) {
    Object status =
        r.getAdditionalProperties() == null ? null : r.getAdditionalProperties().get("status");
    if (status instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    return Map.of();
  }
}
