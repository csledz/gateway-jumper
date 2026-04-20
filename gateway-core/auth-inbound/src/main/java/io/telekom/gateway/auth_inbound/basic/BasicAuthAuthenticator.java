// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.basic;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.api.InboundAuthenticator;
import io.telekom.gateway.auth_inbound.store.CredentialStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * RFC 7617 Basic authenticator. Decodes the {@code Authorization: Basic ...} header, splits on the
 * first colon, and delegates identifier + verifier to the {@link CredentialStore}.
 */
@Slf4j
public class BasicAuthAuthenticator implements InboundAuthenticator {

  private static final String PREFIX = "Basic ";

  private final CredentialStore store;

  public BasicAuthAuthenticator(CredentialStore store) {
    this.store = store;
  }

  @Override
  public Mono<AuthContext> authenticate(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(PREFIX)) {
      return Mono.empty();
    }
    String encoded = header.substring(PREFIX.length()).trim();
    if (encoded.isEmpty()) {
      return Mono.empty();
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      log.warn("malformed Basic header traceId={}", traceId(exchange));
      return Mono.empty();
    }
    String decoded = new String(raw, StandardCharsets.UTF_8);
    int sep = decoded.indexOf(':');
    if (sep <= 0 || sep == decoded.length() - 1) {
      log.warn("Basic header missing colon separator traceId={}", traceId(exchange));
      return Mono.empty();
    }
    String identifier = decoded.substring(0, sep);
    String verifier = decoded.substring(sep + 1);
    return Mono.fromCallable(() -> store.resolveBasic(identifier, verifier))
        .flatMap(
            opt ->
                opt.map(
                        p ->
                            Mono.just(
                                new AuthContext(
                                    p.principalId(),
                                    null,
                                    Map.of(),
                                    p.scopes(),
                                    AuthContext.Type.BASIC,
                                    traceId(exchange))))
                    .orElseGet(
                        () -> {
                          log.warn(
                              "Basic lookup failed identifier={} traceId={}",
                              identifier,
                              traceId(exchange));
                          return Mono.empty();
                        }));
  }

  private static String traceId(ServerWebExchange exchange) {
    String id = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
    return id == null ? exchange.getRequest().getId() : id;
  }
}
