// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.testkit.hydra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Reactive client for obtaining OAuth2 tokens from the Ory Hydra instance that runs in the
 * gateway-core test docker-compose landscape.
 *
 * <p>This class is deliberately <em>not</em> a Spring bean; sibling integration-test suites can
 * instantiate it directly from a Cucumber step, a JUnit extension, or a {@code @BeforeAll} hook.
 *
 * <p><b>Security:</b> tokens are returned via {@code Mono<String>} to callers and never logged,
 * persisted, or included in exception messages. If you need to debug the token exchange, inspect
 * the HTTP status and response body via a reactor-netty wire-log at DEBUG level <em>outside</em>
 * this library.
 *
 * <p><b>Configuration:</b> the client is bootstrapped from a JSON file produced by the {@code
 * hydra-seed} init container. The file path is resolved in the following order:
 *
 * <ol>
 *   <li>constructor argument
 *   <li>the {@code HYDRA_CLIENTS_FILE} environment variable
 *   <li>the default {@code /secrets/hydra-clients.json}
 * </ol>
 */
@Slf4j
public final class HydraClient {

  /** Environment variable used to override the default secrets-file path. */
  public static final String ENV_HYDRA_CLIENTS_FILE = "HYDRA_CLIENTS_FILE";

  private static final String DEFAULT_CLIENTS_FILE = "/secrets/hydra-clients.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ClientRegistry registry;
  private final HttpClient httpClient;

  /** Creates a client using the path resolved from env / default. */
  public HydraClient() {
    this(resolveDefaultPath());
  }

  /** Creates a client from an explicit JSON file path. */
  public HydraClient(Path clientsFile) {
    this(clientsFile, HttpClient.create());
  }

  /** Full DI constructor, mainly for unit tests. */
  public HydraClient(Path clientsFile, HttpClient httpClient) {
    Objects.requireNonNull(clientsFile, "clientsFile");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.registry = loadRegistry(clientsFile);
    log.info(
        "HydraClient initialised from {} (issuer={}, {} client(s))",
        clientsFile,
        registry.issuer(),
        registry.clients().size());
  }

  /**
   * Requests an access token from Hydra using the {@code client_credentials} grant.
   *
   * @param clientAlias logical name of the client in {@code hydra-clients.json} (e.g. {@code
   *     gateway-test-cc})
   * @param scopes scopes to request; may be empty
   * @return a cold {@link Mono} emitting the raw {@code access_token} string. The value is never
   *     cached by this library; callers that need to reuse a token should cache it themselves.
   */
  public Mono<String> clientCredentialsToken(String clientAlias, List<String> scopes) {
    Objects.requireNonNull(clientAlias, "clientAlias");
    ClientEntry entry = registry.clients().get(clientAlias);
    if (entry == null) {
      return Mono.error(
          new IllegalArgumentException("no client with alias '" + clientAlias + "' in registry"));
    }
    String scopeParam = scopes == null ? "" : String.join(" ", scopes);
    String body =
        scopeParam.isEmpty()
            ? "grant_type=client_credentials"
            : "grant_type=client_credentials&scope="
                + URLEncoder.encode(scopeParam, StandardCharsets.UTF_8);
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (entry.clientId() + ":" + entry.clientSecret()).getBytes(StandardCharsets.UTF_8));
    return httpClient
        .headers(
            h -> {
              h.set(
                  HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
              h.set(HttpHeaderNames.ACCEPT, "application/json");
              h.set(HttpHeaderNames.AUTHORIZATION, "Basic " + basic);
            })
        .post()
        .uri(entry.tokenEndpoint())
        .send((req, out) -> out.sendString(Mono.just(body)))
        .responseSingle(
            (res, bytes) ->
                bytes
                    .asString(StandardCharsets.UTF_8)
                    .defaultIfEmpty("")
                    .flatMap(
                        payload -> {
                          int status = res.status().code();
                          if (status != 200) {
                            // Never echo the response body — it may contain diagnostic
                            // info that includes secrets in edge cases.
                            return Mono.error(
                                new IllegalStateException(
                                    "hydra token endpoint returned HTTP " + status));
                          }
                          return extractAccessToken(payload);
                        }));
  }

  /** The issuer URL reported by the seed file (trailing slash preserved). */
  public String issuer() {
    return registry.issuer();
  }

  /** The JWKS URI reported by the seed file. */
  public String jwksUri() {
    return registry.jwksUri();
  }

  /** The shared token endpoint reported by the seed file. */
  public String tokenEndpoint() {
    return registry.tokenEndpoint();
  }

  // ---- internals ---------------------------------------------------------

  private static Path resolveDefaultPath() {
    String fromEnv = System.getenv(ENV_HYDRA_CLIENTS_FILE);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return Path.of(fromEnv);
    }
    return Path.of(DEFAULT_CLIENTS_FILE);
  }

  private static ClientRegistry loadRegistry(Path file) {
    if (!Files.isReadable(file)) {
      throw new IllegalStateException(
          "hydra clients file not readable: "
              + file
              + " (set "
              + ENV_HYDRA_CLIENTS_FILE
              + " or run docker-compose up hydra-seed)");
    }
    try {
      JsonNode root = MAPPER.readTree(file.toFile());
      String issuer = root.path("issuer").asText(null);
      String tokenEndpoint = root.path("token_endpoint").asText(null);
      String jwksUri = root.path("jwks_uri").asText(null);
      JsonNode clients = root.path("clients");
      if (!clients.isObject()) {
        throw new IllegalStateException("hydra clients file missing 'clients' object");
      }
      Map<String, ClientEntry> byAlias = new HashMap<>();
      clients
          .fields()
          .forEachRemaining(
              entry -> {
                JsonNode c = entry.getValue();
                byAlias.put(
                    entry.getKey(),
                    new ClientEntry(
                        c.path("client_id").asText(),
                        c.path("client_secret").asText(),
                        c.path("token_endpoint").asText(tokenEndpoint)));
              });
      return new ClientRegistry(issuer, tokenEndpoint, jwksUri, Map.copyOf(byAlias));
    } catch (IOException e) {
      throw new IllegalStateException("failed to parse hydra clients file: " + file, e);
    }
  }

  private static Mono<String> extractAccessToken(String payload) {
    try {
      JsonNode node = MAPPER.readTree(payload);
      JsonNode at = node.get("access_token");
      if (at == null || at.isNull() || at.asText().isEmpty()) {
        return Mono.error(new IllegalStateException("hydra response had no access_token"));
      }
      return Mono.just(at.asText());
    } catch (IOException e) {
      return Mono.error(new IllegalStateException("failed to parse hydra token response", e));
    }
  }

  // ---- value types -------------------------------------------------------

  private record ClientEntry(String clientId, String clientSecret, String tokenEndpoint) {}

  private record ClientRegistry(
      String issuer, String tokenEndpoint, String jwksUri, Map<String, ClientEntry> clients) {}
}
