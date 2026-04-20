// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.apikey;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.api.InboundAuthenticator;
import io.telekom.gateway.auth_inbound.store.CredentialStore;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API-key authenticator. Reads the configured header (default {@code X-API-Key}), falls back to a
 * query parameter when configured, and delegates to the {@link CredentialStore} for a constant-time
 * digest compare.
 */
@Slf4j
public class ApiKeyAuthenticator implements InboundAuthenticator {

  private final CredentialStore store;
  private final String headerName;
  private final String queryParam;

  public ApiKeyAuthenticator(CredentialStore store, String headerName, String queryParam) {
    this.store = store;
    this.headerName = headerName == null || headerName.isBlank() ? "X-API-Key" : headerName;
    this.queryParam = queryParam;
  }

  @Override
  public Mono<AuthContext> authenticate(ServerWebExchange exchange) {
    String headerValue = exchange.getRequest().getHeaders().getFirst(headerName);
    String queryValue =
        (headerValue == null && queryParam != null)
            ? exchange.getRequest().getQueryParams().getFirst(queryParam)
            : null;
    final String presented = headerValue != null ? headerValue : queryValue;
    if (presented == null || presented.isEmpty()) {
      return Mono.empty();
    }
    return Mono.fromCallable(() -> store.resolveApiKey(presented))
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
                                    AuthContext.Type.APIKEY,
                                    traceId(exchange))))
                    .orElseGet(
                        () -> {
                          log.warn("api-key lookup failed traceId={}", traceId(exchange));
                          return Mono.empty();
                        }));
  }

  private static String traceId(ServerWebExchange exchange) {
    String id = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
    return id == null ? exchange.getRequest().getId() : id;
  }
}
