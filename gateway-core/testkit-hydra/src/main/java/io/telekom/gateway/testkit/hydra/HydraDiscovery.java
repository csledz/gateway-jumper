// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.testkit.hydra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Reactive fetcher for Hydra's {@code /.well-known/openid-configuration} document.
 *
 * <p>The discovery document is fetched lazily on the first call and then cached for the lifetime of
 * this instance — the test landscape is ephemeral so there is no reason to re-fetch.
 */
@Slf4j
public final class HydraDiscovery {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String issuer;
  private final HttpClient httpClient;
  private final AtomicReference<Mono<JsonNode>> cached = new AtomicReference<>();

  /**
   * @param issuer issuer URL, e.g. {@code http://localhost:4444/} (trailing slash optional)
   */
  public HydraDiscovery(String issuer) {
    this(issuer, HttpClient.create());
  }

  public HydraDiscovery(String issuer, HttpClient httpClient) {
    this.issuer = normalise(Objects.requireNonNull(issuer, "issuer"));
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  /** Returns the full discovery document as a {@link JsonNode}. Cached after first success. */
  public Mono<JsonNode> document() {
    Mono<JsonNode> current = cached.get();
    if (current != null) {
      return current;
    }
    Mono<JsonNode> fetched = fetch().cache();
    if (cached.compareAndSet(null, fetched)) {
      return fetched;
    }
    return cached.get();
  }

  /** Convenience: the {@code token_endpoint} claim. */
  public Mono<String> tokenEndpoint() {
    return document().map(d -> d.path("token_endpoint").asText());
  }

  /** Convenience: the {@code jwks_uri} claim. */
  public Mono<String> jwksUri() {
    return document().map(d -> d.path("jwks_uri").asText());
  }

  /** Convenience: the {@code issuer} claim. */
  public Mono<String> issuerClaim() {
    return document().map(d -> d.path("issuer").asText());
  }

  // ---- internals ---------------------------------------------------------

  private Mono<JsonNode> fetch() {
    String url = issuer + ".well-known/openid-configuration";
    log.debug("fetching OIDC discovery document from {}", url);
    return httpClient
        .headers(h -> h.set(HttpHeaderNames.ACCEPT, "application/json"))
        .get()
        .uri(url)
        .responseSingle(
            (res, bytes) ->
                bytes
                    .asString(StandardCharsets.UTF_8)
                    .defaultIfEmpty("")
                    .flatMap(
                        payload -> {
                          int status = res.status().code();
                          if (status != 200) {
                            return Mono.error(
                                new IllegalStateException(
                                    "hydra discovery endpoint returned HTTP " + status));
                          }
                          try {
                            return Mono.just(MAPPER.readTree(payload));
                          } catch (IOException e) {
                            return Mono.error(
                                new IllegalStateException("failed to parse discovery document", e));
                          }
                        }));
  }

  private static String normalise(String issuer) {
    return issuer.endsWith("/") ? issuer : issuer + "/";
  }
}
