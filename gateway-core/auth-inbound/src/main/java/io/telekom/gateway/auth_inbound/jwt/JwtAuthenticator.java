// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.jwt;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.api.InboundAuthenticator;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * JWT authenticator backed by a {@link ReactiveJwtDecoder} (Spring's Nimbus-based decoder handles
 * JWKS fetch, kid cache, signature + iss + exp + nbf validation). The scopes claim name and
 * audience rules are decoded by the supplied {@link ReactiveJwtAuthenticationConverter}.
 */
@Slf4j
public class JwtAuthenticator implements InboundAuthenticator {

  private static final String BEARER_PREFIX = "Bearer ";

  private final ReactiveJwtDecoder decoder;
  private final String scopesClaim;

  public JwtAuthenticator(ReactiveJwtDecoder decoder, String scopesClaim) {
    this.decoder = decoder;
    this.scopesClaim = scopesClaim == null || scopesClaim.isBlank() ? "scope" : scopesClaim;
  }

  @Override
  public Mono<AuthContext> authenticate(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Mono.empty();
    }
    String tokenValue = header.substring(BEARER_PREFIX.length()).trim();
    if (tokenValue.isEmpty()) {
      return Mono.empty();
    }
    return decoder
        .decode(tokenValue)
        .map(
            jwt ->
                new AuthContext(
                    subject(jwt),
                    jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
                    java.util.Map.copyOf(jwt.getClaims()),
                    extractScopes(jwt),
                    AuthContext.Type.JWT,
                    traceId(exchange)))
        .onErrorResume(
            err -> {
              log.warn(
                  "JWT validation failed traceId={} reason={}",
                  traceId(exchange),
                  err.getClass().getSimpleName());
              return Mono.empty();
            });
  }

  private String subject(Jwt jwt) {
    return jwt.getSubject() != null ? jwt.getSubject() : jwt.getClaimAsString("client_id");
  }

  private Set<String> extractScopes(Jwt jwt) {
    Object raw = jwt.getClaim(scopesClaim);
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
