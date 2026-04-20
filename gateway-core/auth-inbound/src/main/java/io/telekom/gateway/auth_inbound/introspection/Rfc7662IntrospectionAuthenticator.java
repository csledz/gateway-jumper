// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.introspection;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.api.InboundAuthenticator;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * RFC 7662 opaque-token introspection authenticator. Delegates the HTTP call to a Spring {@link
 * ReactiveOpaqueTokenIntrospector} and caches successful results briefly. Only {@code active:true}
 * responses yield an {@link AuthContext}; {@code active:false} or any error completes empty.
 */
@Slf4j
public class Rfc7662IntrospectionAuthenticator implements InboundAuthenticator {

  private static final String BEARER_PREFIX = "Bearer ";

  private final ReactiveOpaqueTokenIntrospector introspector;
  private final Cache<String, AuthContext> positiveCache;

  public Rfc7662IntrospectionAuthenticator(
      ReactiveOpaqueTokenIntrospector introspector, Duration positiveTtl) {
    this.introspector = introspector;
    this.positiveCache =
        Caffeine.newBuilder().expireAfterWrite(positiveTtl).maximumSize(10_000).build();
  }

  @Override
  public Mono<AuthContext> authenticate(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Mono.empty();
    }
    String opaque = header.substring(BEARER_PREFIX.length()).trim();
    if (opaque.isEmpty()) {
      return Mono.empty();
    }
    AuthContext cached = positiveCache.getIfPresent(opaque);
    if (cached != null) {
      return Mono.just(cached.withTraceId(traceId(exchange)));
    }
    return introspector
        .introspect(opaque)
        .map(
            principal ->
                new AuthContext(
                    principal.getName(),
                    asString(principal.getAttribute("iss")),
                    Map.copyOf(principal.getAttributes()),
                    extractScopes(principal.getAttribute("scope")),
                    AuthContext.Type.INTROSPECTION,
                    traceId(exchange)))
        .doOnNext(ctx -> positiveCache.put(opaque, ctx))
        .onErrorResume(
            OAuth2IntrospectionException.class,
            err -> {
              log.warn(
                  "introspection failed traceId={} reason={}",
                  traceId(exchange),
                  err.getClass().getSimpleName());
              return Mono.empty();
            });
  }

  private String asString(Object v) {
    return v == null ? null : v.toString();
  }

  private Set<String> extractScopes(Object raw) {
    if (raw instanceof String s) {
      Set<String> out = new HashSet<>();
      for (String part : s.split("\\s+")) {
        if (!part.isBlank()) {
          out.add(part);
        }
      }
      return Set.copyOf(out);
    }
    if (raw instanceof Collection<?> c) {
      Set<String> out = new HashSet<>();
      for (Object v : c) {
        if (v != null) {
          out.add(v.toString());
        }
      }
      return Set.copyOf(out);
    }
    return Set.of();
  }

  private static String traceId(ServerWebExchange exchange) {
    String id = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
    return id == null ? exchange.getRequest().getId() : id;
  }
}
