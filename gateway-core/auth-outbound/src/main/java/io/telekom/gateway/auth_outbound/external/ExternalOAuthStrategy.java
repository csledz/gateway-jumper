// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.external;

import io.telekom.gateway.auth_outbound.api.ClientSecretResolver;
import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import io.telekom.gateway.auth_outbound.cache.TieredTokenCache;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * OAuth2 {@code client_credentials} grant against the policy's {@code tokenEndpoint}; caches the
 * returned Bearer token in {@link TieredTokenCache} and sets it as the outbound Authorization.
 */
@Slf4j
public class ExternalOAuthStrategy implements OutboundTokenStrategy {

  private final WebClient webClient;
  private final TieredTokenCache cache;
  private final ClientSecretResolver secrets;

  public ExternalOAuthStrategy(
      WebClient webClient, TieredTokenCache cache, ClientSecretResolver secrets) {
    this.webClient = webClient;
    this.cache = cache;
    this.secrets = secrets;
  }

  @Override
  public Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy) {
    TieredTokenCache.Key key =
        new TieredTokenCache.Key(policy.tokenEndpoint(), policy.clientId(), policy.scopes());
    return cache
        .getOrFetch(key, () -> fetchToken(policy))
        .doOnNext(
            tok ->
                exchange
                    .getAttributes()
                    .put(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR, "Bearer " + tok))
        .then();
  }

  private Mono<TieredTokenCache.FetchResult> fetchToken(OutboundAuthPolicy policy) {
    String secret = secrets.resolve(policy.clientSecretRef()).orElse(null);
    if (secret == null) {
      log.warn("client secret not resolvable for ref={}", policy.clientSecretRef());
      return Mono.error(new IllegalStateException("missing client secret"));
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    if (policy.scopes() != null && !policy.scopes().isEmpty()) {
      form.add("scope", String.join(" ", policy.scopes()));
    }
    return webClient
        .post()
        .uri(policy.tokenEndpoint())
        .headers(h -> h.setBasicAuth(policy.clientId(), secret))
        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData(form))
        .retrieve()
        .bodyToMono(Map.class)
        .map(ExternalOAuthStrategy::toFetchResult);
  }

  @SuppressWarnings("unchecked")
  private static TieredTokenCache.FetchResult toFetchResult(Map<?, ?> body) {
    Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) body);
    String token = (String) m.get("access_token");
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("token endpoint returned no access_token");
    }
    Number expiresIn = (Number) m.getOrDefault("expires_in", 300);
    List<String> scopes = List.of();
    return new TieredTokenCache.FetchResult(token, Duration.ofSeconds(expiresIn.longValue()));
  }
}
