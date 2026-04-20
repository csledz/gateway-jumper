// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Redacts sensitive parameters and headers before they become part of span names, tags or log
 * lines.
 *
 * <p>Ported from jumper's {@code TracingConfiguration} (lines 31-100) and extended with {@link
 * #redactHeaderValue(String, String)} so that header-based propagation scrubs bearer tokens and
 * similar secrets consistently.
 */
@Slf4j
public class SecretRedactor {

  /**
   * Default patterns matching query parameter names that must never appear in spans.
   *
   * <p>The list covers AWS sig-v4 params ({@code X-Amz-*}, {@code sig}, {@code signature}), OAuth2
   * authorization-code / token-endpoint params ({@code access_token}, {@code refresh_token}, {@code
   * id_token}, {@code code}, {@code state}, {@code token}, {@code jwt}), and common standalone
   * credentials ({@code password}, {@code client_secret}, {@code api_key}). Patterns are matched
   * case-sensitively as regex against the full parameter name.
   */
  public static final List<String> DEFAULT_QUERY_FILTERS =
      List.of(
          "X-Amz-.*",
          "sig",
          "signature",
          "access_token",
          "refresh_token",
          "id_token",
          "code",
          "state",
          "token",
          "jwt",
          "password",
          "client_secret",
          "api[_-]?key");

  /**
   * Header names whose values must be redacted (case-insensitive).
   *
   * <p>Covers RFC 7235 auth ({@code Authorization}, {@code Proxy-Authorization}), cookies, Kong-era
   * / gateway-core API-key conventions ({@code X-API-Key}, {@code X-Auth-Token}), AWS session
   * tokens, and the Consumer-Token header used by the mesh-federation strategy to forward the
   * original inbound bearer to the next zone ({@code X-Consumer-Token}).
   */
  public static final Set<String> SENSITIVE_HEADER_NAMES =
      Set.of(
          "authorization",
          "proxy-authorization",
          "cookie",
          "set-cookie",
          "x-api-key",
          "x-amz-security-token",
          "x-auth-token",
          "x-consumer-token");

  private static final String REDACTED = "[redacted]";

  private final List<Pattern> compiledQueryFilterPatterns;

  /** Default constructor uses {@link #DEFAULT_QUERY_FILTERS}. */
  public SecretRedactor() {
    this(Collections.emptyList());
  }

  /**
   * @param extraQueryFilters additional regex patterns applied on top of {@link
   *     #DEFAULT_QUERY_FILTERS}.
   */
  public SecretRedactor(List<String> extraQueryFilters) {
    List<String> all = new ArrayList<>(DEFAULT_QUERY_FILTERS);
    if (extraQueryFilters != null) {
      all.addAll(extraQueryFilters);
    }
    this.compiledQueryFilterPatterns = all.stream().map(Pattern::compile).toList();
  }

  /** Returns the compiled patterns used to match query parameter names for redaction. */
  public List<Pattern> compiledQueryFilterPatterns() {
    return compiledQueryFilterPatterns;
  }

  /**
   * Strips sensitive query parameters from a URL.
   *
   * <p>If the URL cannot be parsed the query string is removed altogether to avoid leaking secrets
   * through tracing or logs.
   */
  public String filterQueryParams(String urlString) {
    if (urlString == null || !urlString.contains("?") || compiledQueryFilterPatterns.isEmpty()) {
      return urlString;
    }
    try {
      boolean encoded = urlString.contains("%");
      UriComponents uriComponents = UriComponentsBuilder.fromUriString(urlString).build(encoded);
      MultiValueMap<String, String> filtered = new LinkedMultiValueMap<>();
      uriComponents
          .getQueryParams()
          .forEach(
              (key, values) -> {
                if (compiledQueryFilterPatterns.stream().noneMatch(p -> p.matcher(key).matches())) {
                  filtered.put(key, values);
                }
              });
      return UriComponentsBuilder.newInstance()
          .scheme(uriComponents.getScheme())
          .host(uriComponents.getHost())
          .port(uriComponents.getPort())
          .path(Optional.ofNullable(uriComponents.getPath()).orElse(""))
          .queryParams(filtered)
          .fragment(uriComponents.getFragment())
          .build()
          .toUriString();
    } catch (IllegalArgumentException e) {
      log.warn(
          "Failed to parse URL for query parameter filtering: {}. Stripping all query parameters."
              + " Error: {}",
          urlString,
          e.getMessage());
      int qIdx = urlString.indexOf('?');
      return qIdx > 0 ? urlString.substring(0, qIdx) : urlString;
    }
  }

  /** Returns the header value, or {@value #REDACTED} when the header is sensitive. */
  public String redactHeaderValue(String headerName, String value) {
    if (headerName == null || value == null) {
      return value;
    }
    String lower = headerName.toLowerCase(Locale.ROOT);
    return SENSITIVE_HEADER_NAMES.contains(lower) ? REDACTED : value;
  }
}
