// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.filter;

import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@link WebFilter} at order 600. Reads the outbound {@link OutboundAuthPolicy} resolved by an
 * earlier stage and dispatches to the matching {@link OutboundTokenStrategy}. Strategies publish
 * their Authorization header value to {@link OutboundTokenStrategy#OUTBOUND_AUTH_HEADER_ATTR}; a
 * subsequent proxy forwarder applies it to the upstream request.
 */
@Slf4j
public class OutboundAuthFilter implements WebFilter, Ordered {

  public static final int ORDER = 600;
  public static final String POLICY_ATTR = "io.telekom.gateway.outboundPolicy";

  private final Map<OutboundAuthPolicy.Type, OutboundTokenStrategy> strategies;
  private final Function<ServerWebExchange, Optional<OutboundAuthPolicy>> policyLookup;

  public OutboundAuthFilter(
      Map<OutboundAuthPolicy.Type, OutboundTokenStrategy> strategies,
      Function<ServerWebExchange, Optional<OutboundAuthPolicy>> policyLookup) {
    EnumMap<OutboundAuthPolicy.Type, OutboundTokenStrategy> copy =
        new EnumMap<>(OutboundAuthPolicy.Type.class);
    copy.putAll(strategies);
    this.strategies = copy;
    this.policyLookup = policyLookup;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    Optional<OutboundAuthPolicy> policy = policyLookup.apply(exchange);
    if (policy.isEmpty()) {
      return chain.filter(exchange);
    }
    OutboundAuthPolicy p = policy.get();
    OutboundTokenStrategy strategy = strategies.get(p.type());
    if (strategy == null) {
      log.warn("no strategy configured for outbound type {}", p.type());
      return chain.filter(exchange);
    }
    exchange.getAttributes().put(POLICY_ATTR, p);
    return strategy.apply(exchange, p).then(chain.filter(exchange));
  }

  /** Default lookup: reads the policy from the {@link #POLICY_ATTR} exchange attribute. */
  public static Function<ServerWebExchange, Optional<OutboundAuthPolicy>> attributeLookup() {
    return exchange -> {
      Object attr = exchange.getAttributes().get(POLICY_ATTR);
      return attr instanceof OutboundAuthPolicy p ? Optional.of(p) : Optional.empty();
    };
  }
}
