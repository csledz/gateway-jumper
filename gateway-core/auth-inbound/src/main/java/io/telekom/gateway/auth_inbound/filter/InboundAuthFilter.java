// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.filter;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.api.InboundAuthenticator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@link WebFilter} at order 200. Picks an authenticator based on the resolved route's declared
 * auth type (or the {@code X-Auth-Type} header for demos), runs it, and publishes the resulting
 * {@link AuthContext} to the exchange attributes. Rejects with 401 when the chosen authenticator
 * completes empty.
 */
@Slf4j
public class InboundAuthFilter implements WebFilter, Ordered {

  public static final int ORDER = 200;
  public static final String AUTH_CONTEXT_ATTR = "io.telekom.gateway.authContext";
  public static final String TYPE_HEADER = "X-Auth-Type";

  private final Map<AuthContext.Type, InboundAuthenticator> byType;
  private final Function<ServerWebExchange, Optional<AuthContext.Type>> typeResolver;

  public InboundAuthFilter(
      Map<AuthContext.Type, InboundAuthenticator> byType,
      Function<ServerWebExchange, Optional<AuthContext.Type>> typeResolver) {
    EnumMap<AuthContext.Type, InboundAuthenticator> copy = new EnumMap<>(AuthContext.Type.class);
    copy.putAll(byType);
    this.byType = copy;
    this.typeResolver = typeResolver;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    Optional<AuthContext.Type> type = typeResolver.apply(exchange);
    if (type.isEmpty()) {
      // route does not require inbound auth
      return chain.filter(exchange);
    }
    InboundAuthenticator authenticator = byType.get(type.get());
    if (authenticator == null) {
      return reject401(exchange, "auth type not configured: " + type.get());
    }
    return authenticator
        .authenticate(exchange)
        .flatMap(
            ctx -> {
              exchange.getAttributes().put(AUTH_CONTEXT_ATTR, ctx);
              return chain.filter(exchange);
            })
        .switchIfEmpty(Mono.defer(() -> reject401(exchange, "auth failed: " + type.get())));
  }

  private Mono<Void> reject401(ServerWebExchange exchange, String reason) {
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    exchange.getResponse().getHeaders().add("WWW-Authenticate", "Bearer");
    log.debug("inbound auth rejection: {}", reason);
    return exchange.getResponse().setComplete();
  }

  /** Default type resolver driven by the {@code X-Auth-Type} header. */
  public static Function<ServerWebExchange, Optional<AuthContext.Type>> headerTypeResolver() {
    return exchange -> {
      List<String> values = exchange.getRequest().getHeaders().getOrDefault(TYPE_HEADER, List.of());
      if (values.isEmpty()) {
        return Optional.empty();
      }
      try {
        return Optional.of(AuthContext.Type.valueOf(values.get(0).trim().toUpperCase()));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    };
  }
}
