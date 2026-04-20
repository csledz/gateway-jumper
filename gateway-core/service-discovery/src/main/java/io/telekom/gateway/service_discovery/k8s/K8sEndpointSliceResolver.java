// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.k8s;

import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Watches Kubernetes {@code discovery.k8s.io/v1 EndpointSlice} resources and maintains an in-memory
 * cache keyed by {@code (namespace, service)}. The cache is updated reactively via the standard
 * fabric8 watch callbacks so reads are O(1) and non-blocking.
 *
 * <p>EndpointSlice is preferred over the legacy {@code Endpoints} API because it scales to services
 * with many pods without a single oversized object per service.
 */
@Slf4j
public class K8sEndpointSliceResolver implements ServiceResolver, Watcher<EndpointSlice> {

  public static final String SCHEME = "k8s";

  /** Standard label set by the EndpointSlice controller to point back at the owning Service. */
  public static final String SERVICE_NAME_LABEL = "kubernetes.io/service-name";

  private final KubernetesClient client;
  private final ConcurrentMap<Key, List<ServiceEndpoint>> cache = new ConcurrentHashMap<>();
  private Watch watch;

  public K8sEndpointSliceResolver(KubernetesClient client) {
    this.client = Objects.requireNonNull(client);
  }

  @PostConstruct
  public void start() {
    // Prime cache with existing slices, then install watch for deltas.
    client
        .discovery()
        .v1()
        .endpointSlices()
        .inAnyNamespace()
        .list()
        .getItems()
        .forEach(this::apply);
    this.watch = client.discovery().v1().endpointSlices().inAnyNamespace().watch(this);
    log.info("EndpointSlice watch started; primed cache with {} service(s)", cache.size());
  }

  @PreDestroy
  public void stop() {
    if (watch != null) {
      watch.close();
    }
  }

  @Override
  public String scheme() {
    return SCHEME;
  }

  @Override
  public Mono<List<ServiceEndpoint>> resolve(ServiceRef ref) {
    // Cache reads are O(1) on a ConcurrentHashMap; a direct Mono.just avoids the lambda allocation
    // that Mono.fromSupplier would incur on every hot-path request.
    return Mono.just(cache.getOrDefault(new Key(ref.namespace(), ref.name()), List.of()));
  }

  // ---- Watcher<EndpointSlice> ----------------------------------------------------------------

  @Override
  public void eventReceived(Action action, EndpointSlice slice) {
    switch (action) {
      case ADDED, MODIFIED -> apply(slice);
      case DELETED -> remove(slice);
      default -> log.debug("Ignoring EndpointSlice action {} for {}", action, sliceName(slice));
    }
  }

  @Override
  public void onClose(WatcherException cause) {
    if (cause != null) {
      log.warn("EndpointSlice watch closed: {}", cause.getMessage());
    }
  }

  // ---- Internals -----------------------------------------------------------------------------

  void apply(EndpointSlice slice) {
    Key key = keyOf(slice);
    if (key == null) {
      return;
    }
    cache.put(key, toEndpoints(slice));
    log.debug("EndpointSlice cache updated: {} -> {} endpoint(s)", key, cache.get(key).size());
  }

  void remove(EndpointSlice slice) {
    Key key = keyOf(slice);
    if (key != null) {
      cache.remove(key);
    }
  }

  private static Key keyOf(EndpointSlice slice) {
    Map<String, String> labels = slice.getMetadata().getLabels();
    if (labels == null) {
      return null;
    }
    String service = labels.get(SERVICE_NAME_LABEL);
    if (service == null) {
      return null;
    }
    return new Key(slice.getMetadata().getNamespace(), service);
  }

  private static List<ServiceEndpoint> toEndpoints(EndpointSlice slice) {
    List<EndpointPort> ports = slice.getPorts();
    int port =
        (ports == null || ports.isEmpty() || ports.get(0).getPort() == null)
            ? 80
            : ports.get(0).getPort();
    List<Endpoint> eps = slice.getEndpoints();
    if (eps == null) {
      return List.of();
    }
    List<ServiceEndpoint> out = new ArrayList<>(eps.size());
    for (Endpoint e : eps) {
      boolean ready =
          e.getConditions() == null
              || e.getConditions().getReady() == null
              || Boolean.TRUE.equals(e.getConditions().getReady());
      List<String> addrs = e.getAddresses();
      if (addrs == null) {
        continue;
      }
      for (String addr : addrs) {
        out.add(new ServiceEndpoint(addr, port, "http", ready, 1));
      }
    }
    return List.copyOf(out);
  }

  private static String sliceName(EndpointSlice slice) {
    return slice.getMetadata().getNamespace() + "/" + slice.getMetadata().getName();
  }

  /** Composite cache key — fabric8 doesn't expose a built-in one for this use. */
  record Key(String namespace, String service) {}
}
