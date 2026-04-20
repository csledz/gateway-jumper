// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.steps;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.telekom.gateway.cli.admin.AdminClient;
import io.telekom.gateway.cli.config.KubeconfigLoader;
import io.telekom.gateway.cli.k8s.GatewayResources;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.mockserver.integration.ClientAndServer;

/**
 * Holds the per-scenario test fixtures: fabric8 mock server, admin API MockServer, captured stdio.
 * A single instance is kept in a ThreadLocal so multiple step classes see the same state within a
 * scenario without Spring DI.
 */
public final class World {

  private static final ThreadLocal<World> CURRENT = ThreadLocal.withInitial(World::new);

  public static World current() {
    return CURRENT.get();
  }

  /** Tear everything down at the end of a scenario. */
  public static void reset() {
    World w = CURRENT.get();
    w.close();
    CURRENT.remove();
  }

  public KubernetesMockServer kubeMock;
  public KubernetesClient kubeClient;
  public ClientAndServer adminMock;
  public String adminBaseUrl;
  public int exitCode;
  public String stdout = "";
  public String stderr = "";

  public KubeconfigLoader kubeconfigLoader() {
    return new KubeconfigLoader() {
      @Override
      public KubernetesClient load(String context, String namespace) {
        return kubeClient;
      }
    };
  }

  public Function<String, AdminClient> adminClientFactory() {
    return url -> new AdminClient(adminBaseUrl == null ? url : adminBaseUrl);
  }

  public void ensureKubeMock() {
    if (kubeMock == null) {
      kubeMock = new KubernetesMockServer();
      kubeMock.start();
      kubeClient = kubeMock.createClient();
    }
  }

  public void ensureAdminMock() {
    if (adminMock == null) {
      adminMock = ClientAndServer.startClientAndServer(0);
      adminBaseUrl = "http://localhost:" + adminMock.getLocalPort();
    }
  }

  public void markAdminUnreachable() {
    if (adminMock != null) {
      adminMock.stop();
      adminMock = null;
    }
    // Point at a port we know is closed; AdminClient's WebClient will fail fast.
    adminBaseUrl = "http://127.0.0.1:1";
  }

  public GenericKubernetesResource buildRoute(String namespace, String name) {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("host", name + ".example.com");
    spec.put("path", "/" + name);
    spec.put("upstream", "http://" + name);
    Map<String, Object> status = new HashMap<>();
    status.put("phase", "Ready");
    Map<String, Object> additional = new LinkedHashMap<>();
    additional.put("spec", spec);
    additional.put("status", status);

    return new GenericKubernetesResourceBuilder()
        .withApiVersion(GatewayResources.GROUP + "/" + GatewayResources.VERSION)
        .withKind(GatewayResources.KIND_ROUTE)
        .withNewMetadata()
        .withName(name)
        .withNamespace(namespace)
        .endMetadata()
        .withAdditionalProperties(additional)
        .build();
  }

  public GenericKubernetesResource buildZone(String namespace, String name) {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("url", "https://" + name + ".zones.local");
    spec.put("region", name);
    Map<String, Object> status = new HashMap<>();
    status.put("phase", "Ready");
    Map<String, Object> additional = new LinkedHashMap<>();
    additional.put("spec", spec);
    additional.put("status", status);

    return new GenericKubernetesResourceBuilder()
        .withApiVersion(GatewayResources.GROUP + "/" + GatewayResources.VERSION)
        .withKind(GatewayResources.KIND_ZONE)
        .withNewMetadata()
        .withName(name)
        .withNamespace(namespace)
        .endMetadata()
        .withAdditionalProperties(additional)
        .build();
  }

  public void stubRouteList(String namespace, List<GenericKubernetesResource> routes) {
    String path =
        "/apis/"
            + GatewayResources.GROUP
            + "/"
            + GatewayResources.VERSION
            + "/namespaces/"
            + namespace
            + "/"
            + GatewayResources.PLURAL_ROUTES;
    GenericKubernetesResourceList body = new GenericKubernetesResourceList();
    body.setApiVersion(GatewayResources.GROUP + "/" + GatewayResources.VERSION);
    body.setKind("List");
    body.setMetadata(new ListMetaBuilder().build());
    body.setItems(routes);
    kubeMock.expect().get().withPath(path).andReturn(200, body).always();
  }

  public void stubZone(String namespace, GenericKubernetesResource zone) {
    String path =
        "/apis/"
            + GatewayResources.GROUP
            + "/"
            + GatewayResources.VERSION
            + "/namespaces/"
            + namespace
            + "/"
            + GatewayResources.PLURAL_ZONES
            + "/"
            + zone.getMetadata().getName();
    kubeMock.expect().get().withPath(path).andReturn(200, zone).always();
  }

  private void close() {
    if (kubeClient != null) {
      kubeClient.close();
    }
    if (kubeMock != null) {
      kubeMock.destroy();
    }
    if (adminMock != null) {
      adminMock.stop();
    }
  }
}
