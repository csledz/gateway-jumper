// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Thin reactive client for the admin-status-api. Only surfaces the handful of endpoints the CLI
 * uses. Timeouts are bounded so the CLI never hangs on a dead API.
 */
@Slf4j
public final class AdminClient {

  /**
   * Default base URL (dev): {@value}. Override via {@code GATECTL_ADMIN_URL} or {@code
   * --admin-url}.
   */
  public static final String DEFAULT_BASE_URL = "http://localhost:8080";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WebClient webClient;

  public AdminClient(String baseUrl) {
    String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    this.webClient = WebClient.builder().baseUrl(url).build();
    log.debug("AdminClient bound to {}", url);
  }

  AdminClient(WebClient webClient) {
    this.webClient = webClient;
  }

  /** Resolve the zone health document for a zone (e.g. {@code GET /admin/zones/{zone}/health}). */
  public Mono<ZoneHealth> zoneHealth(String zone) {
    return webClient
        .get()
        .uri("/admin/zones/{zone}/health", zone)
        .retrieve()
        .bodyToMono(String.class)
        .map(body -> parse(body, ZoneHealth.class))
        .onErrorResume(
            WebClientResponseException.class,
            ex ->
                Mono.just(
                    new ZoneHealth(
                        zone, "UNHEALTHY", "HTTP " + ex.getStatusCode().value(), List.of())));
  }

  /** List all zones. Returns the raw maps so the caller can render whatever columns it wants. */
  public Mono<List<Map<String, Object>>> listZones() {
    return webClient
        .get()
        .uri("/admin/zones")
        .retrieve()
        .bodyToMono(String.class)
        .map(body -> parseList(body));
  }

  private static <T> T parse(String body, Class<T> type) {
    try {
      return MAPPER.readValue(body, type);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse admin API response", e);
    }
  }

  private static List<Map<String, Object>> parseList(String body) {
    try {
      return MAPPER.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse admin API list response", e);
    }
  }
}
