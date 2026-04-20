// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.mesh;

import io.telekom.gateway.auth_outbound.api.ClientSecretResolver;
import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import io.telekom.gateway.auth_outbound.cache.TieredTokenCache;
import io.telekom.gateway.auth_outbound.external.ExternalOAuthStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway-to-gateway (mesh) token flow. Fetches a bearer from the peer zone's IdP via {@code
 * internalTokenEndpoint} (same client-credentials machinery as {@link ExternalOAuthStrategy}) and
 * also preserves the original consumer token so the peer can still identify the caller end-to-end.
 */
@Slf4j
public class MeshTokenStrategy implements OutboundTokenStrategy {

  private final ExternalOAuthStrategy delegate;

  public MeshTokenStrategy(
      WebClient webClient, TieredTokenCache cache, ClientSecretResolver secrets) {
    this.delegate = new ExternalOAuthStrategy(webClient, cache, secrets);
  }

  @Override
  public Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy) {
    String consumerToken = originalBearer(exchange);
    if (consumerToken != null) {
      exchange.getAttributes().put(OutboundTokenStrategy.CONSUMER_TOKEN_ATTR, consumerToken);
    }
    OutboundAuthPolicy routed =
        new OutboundAuthPolicy(
            policy.type(),
            policy.clientId(),
            policy.clientSecretRef(),
            policy.internalTokenEndpoint() == null
                ? policy.tokenEndpoint()
                : policy.internalTokenEndpoint(),
            policy.internalTokenEndpoint(),
            policy.scopes(),
            policy.realm(),
            policy.environment(),
            policy.serviceOwner());
    return delegate.apply(exchange, routed);
  }

  private String originalBearer(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ")) {
      return header.substring("Bearer ".length()).trim();
    }
    return null;
  }
}
