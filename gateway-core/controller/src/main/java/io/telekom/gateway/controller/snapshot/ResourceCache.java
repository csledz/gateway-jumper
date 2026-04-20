// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.snapshot;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.telekom.gateway.controller.api.GatewayConsumer;
import io.telekom.gateway.controller.api.GatewayCredential;
import io.telekom.gateway.controller.api.GatewayMeshPeer;
import io.telekom.gateway.controller.api.GatewayPolicy;
import io.telekom.gateway.controller.api.GatewayResource;
import io.telekom.gateway.controller.api.GatewayRoute;
import io.telekom.gateway.controller.api.GatewayZone;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * In-memory cache of all CRD resources the controller has observed. Reconcilers push into this
 * cache; {@link SnapshotBuilder} reads from it.
 */
@Component
public class ResourceCache {

  private final Map<String, GatewayRoute> routes = new ConcurrentHashMap<>();
  private final Map<String, GatewayConsumer> consumers = new ConcurrentHashMap<>();
  private final Map<String, GatewayCredential> credentials = new ConcurrentHashMap<>();
  private final Map<String, GatewayZone> zones = new ConcurrentHashMap<>();
  private final Map<String, GatewayMeshPeer> meshPeers = new ConcurrentHashMap<>();
  private final Map<String, GatewayPolicy> policies = new ConcurrentHashMap<>();

  public void upsertRoute(GatewayRoute r) {
    routes.put(key(r), r);
  }

  public void removeRoute(GatewayRoute r) {
    routes.remove(key(r));
  }

  public void upsertConsumer(GatewayConsumer c) {
    consumers.put(key(c), c);
  }

  public void removeConsumer(GatewayConsumer c) {
    consumers.remove(key(c));
  }

  public void upsertCredential(GatewayCredential c) {
    credentials.put(key(c), c);
  }

  public void removeCredential(GatewayCredential c) {
    credentials.remove(key(c));
  }

  public void upsertZone(GatewayZone z) {
    zones.put(key(z), z);
  }

  public void removeZone(GatewayZone z) {
    zones.remove(key(z));
  }

  public void upsertMeshPeer(GatewayMeshPeer p) {
    meshPeers.put(key(p), p);
  }

  public void removeMeshPeer(GatewayMeshPeer p) {
    meshPeers.remove(key(p));
  }

  public void upsertPolicy(GatewayPolicy p) {
    policies.put(key(p), p);
  }

  public void removePolicy(GatewayPolicy p) {
    policies.remove(key(p));
  }

  public List<GatewayRoute> routesForZone(String zone) {
    return filterByZone(routes.values(), zone);
  }

  public List<GatewayConsumer> consumersForZone(String zone) {
    return filterByZone(consumers.values(), zone);
  }

  public List<GatewayCredential> credentialsForZone(String zone) {
    return filterByZone(credentials.values(), zone);
  }

  public List<GatewayMeshPeer> meshPeersForZone(String zone) {
    return filterByZone(meshPeers.values(), zone);
  }

  public List<GatewayPolicy> policiesForZone(String zone) {
    return filterByZone(policies.values(), zone);
  }

  public GatewayZone zoneByName(String zoneName) {
    return zones.values().stream()
        .filter(z -> z.getSpec() != null && zoneName.equals(z.getSpec().getZoneName()))
        .findFirst()
        .orElse(null);
  }

  public Set<String> knownZones() {
    Set<String> all = new HashSet<>();
    zones.values().stream()
        .filter(z -> z.getSpec() != null && z.getSpec().getZoneName() != null)
        .forEach(z -> all.add(z.getSpec().getZoneName()));
    Consumer<GatewayResource<?>> addZone =
        r -> {
          if (r.getZone() != null) all.add(r.getZone());
        };
    routes.values().forEach(addZone);
    consumers.values().forEach(addZone);
    credentials.values().forEach(addZone);
    meshPeers.values().forEach(addZone);
    policies.values().forEach(addZone);
    return all;
  }

  private static <T extends GatewayResource<?>> List<T> filterByZone(
      Collection<T> all, String zone) {
    return all.stream().filter(r -> zone.equals(r.getZone())).toList();
  }

  private static String key(HasMetadata r) {
    String ns = r.getMetadata() != null ? r.getMetadata().getNamespace() : "";
    String name = r.getMetadata() != null ? r.getMetadata().getName() : "";
    return (ns == null ? "" : ns) + "/" + name;
  }
}
