// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.filter;

import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import io.telekom.gateway.service_discovery.consul.ConsulResolver;
import io.telekom.gateway.service_discovery.dns.StaticDnsResolver;
import io.telekom.gateway.service_discovery.k8s.K8sEndpointSliceResolver;
import io.telekom.gateway.service_discovery.lb.WeightedRoundRobin;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that replaces the jumper/Kong {@code remote_api_url} header flow. For each request
 * whose target URI uses a service-discovery scheme ({@code k8s}, {@code dns}, {@code consul}) the
 * filter resolves one concrete {@link ServiceEndpoint} via the {@link ServiceResolver} and rewrites
 * {@link ServerWebExchangeUtils#GATEWAY_REQUEST_URL_ATTR} so downstream forwarding filters route
 * the request to the real backend.
 */
@Slf4j
public class ServiceDiscoveryFilter implements GlobalFilter, Ordered {

  /** Order 500 — after route-matching, before the Netty routing filter (order 10_000+). */
  public static final int SERVICE_DISCOVERY = 500;

  private static final Set<String> DISCOVERY_SCHEMES =
      Set.of(K8sEndpointSliceResolver.SCHEME, StaticDnsResolver.SCHEME, ConsulResolver.SCHEME);

  private final ServiceResolver resolver;
  private final WeightedRoundRobin picker;

  public ServiceDiscoveryFilter(ServiceResolver resolver, WeightedRoundRobin picker) {
    this.resolver = Objects.requireNonNull(resolver);
    this.picker = Objects.requireNonNull(picker);
  }

  @Override
  public int getOrder() {
    return SERVICE_DISCOVERY;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
    if (target == null || !isDiscoveryScheme(target.getScheme())) {
      return chain.filter(exchange);
    }
    ServiceRef ref = ServiceRef.fromUri(target);
    return resolver
        .resolve(ref)
        .flatMap(list -> rewrite(exchange, chain, list, target))
        .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
  }

  private Mono<Void> rewrite(
      ServerWebExchange exchange,
      GatewayFilterChain chain,
      List<ServiceEndpoint> endpoints,
      URI original) {
    ServiceEndpoint chosen = picker.pick(endpoints);
    if (chosen == null) {
      log.warn("No healthy endpoints for {}", original);
      return chain.filter(exchange);
    }
    URI rewritten = buildRewrittenUri(original, chosen);
    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, rewritten);
    log.debug("Resolved {} -> {}", original, rewritten);
    return chain.filter(exchange);
  }

  /** Preserve path & query from the original; swap scheme/host/port for the resolved endpoint. */
  static URI buildRewrittenUri(URI original, ServiceEndpoint chosen) {
    try {
      return new URI(
          chosen.scheme(),
          original.getUserInfo(),
          chosen.host(),
          chosen.port(),
          original.getRawPath(),
          original.getRawQuery(),
          original.getRawFragment());
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException("Failed to build upstream URI", e);
    }
  }

  private static boolean isDiscoveryScheme(String scheme) {
    return scheme != null && DISCOVERY_SCHEMES.contains(scheme.toLowerCase());
  }
}
