// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.composite;

import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Dispatches resolution to the resolver whose {@link ServiceResolver#scheme()} matches the {@link
 * ServiceRef#scheme()}. This is how we mix k8s, DNS, and Consul routes in the same gateway without
 * forcing every team onto the same service registry.
 */
@Slf4j
public class CompositeResolver implements ServiceResolver {

  private final Map<String, ServiceResolver> byScheme;

  public CompositeResolver(List<ServiceResolver> resolvers) {
    Objects.requireNonNull(resolvers, "resolvers");
    this.byScheme =
        resolvers.stream()
            .collect(Collectors.toUnmodifiableMap(r -> r.scheme().toLowerCase(), r -> r));
    log.info("CompositeResolver registered schemes: {}", byScheme.keySet());
  }

  @Override
  public String scheme() {
    // Composite handles all registered schemes; used only for logging.
    return "composite";
  }

  @Override
  public Mono<List<ServiceEndpoint>> resolve(ServiceRef ref) {
    ServiceResolver delegate = byScheme.get(ref.scheme().toLowerCase());
    if (delegate == null) {
      return Mono.error(
          new IllegalStateException("No resolver registered for scheme: " + ref.scheme()));
    }
    return delegate.resolve(ref);
  }
}
