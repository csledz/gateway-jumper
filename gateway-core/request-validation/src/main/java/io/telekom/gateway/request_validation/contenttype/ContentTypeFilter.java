// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.contenttype;

import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import io.telekom.gateway.request_validation.config.RequestValidationProperties;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Enforces an allow-list of request media types. 415 on mismatch. Idempotent verbs (GET, HEAD,
 * DELETE, OPTIONS) with no body are skipped.
 */
public class ContentTypeFilter implements WebFilter, Ordered {

  public static final int ORDER = 120;
  private final PolicyLookup lookup;
  private final RequestValidationProperties defaults;

  public ContentTypeFilter(PolicyLookup lookup, RequestValidationProperties defaults) {
    this.lookup = lookup;
    this.defaults = defaults;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!requestHasBody(exchange)) {
      return chain.filter(exchange);
    }
    MediaType contentType = exchange.getRequest().getHeaders().getContentType();
    List<String> allowed = allowedTypesFor(exchange);
    if (contentType == null) {
      if (allowed.contains("application/octet-stream") || allowed.contains("*/*")) {
        return chain.filter(exchange);
      }
      return reject(exchange, "missing Content-Type");
    }
    String normalised = contentType.getType() + "/" + contentType.getSubtype();
    if (allowed.contains("*/*") || allowed.contains(normalised)) {
      return chain.filter(exchange);
    }
    return reject(exchange, "content type not allowed: " + normalised);
  }

  private boolean requestHasBody(ServerWebExchange exchange) {
    HttpMethod method = exchange.getRequest().getMethod();
    if (method == null) {
      return false;
    }
    long cl = exchange.getRequest().getHeaders().getContentLength();
    String te = exchange.getRequest().getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING);
    return cl > 0 || (te != null && te.toLowerCase().contains("chunked"));
  }

  private List<String> allowedTypesFor(ServerWebExchange exchange) {
    return lookup
        .find(exchange)
        .map(ValidationPolicy::allowedContentTypes)
        .filter(l -> l != null && !l.isEmpty())
        .orElse(defaults.defaultAllowedContentTypes());
  }

  private Mono<Void> reject(ServerWebExchange exchange, String reason) {
    exchange.getResponse().setStatusCode(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    exchange.getResponse().getHeaders().add("X-Reject-Reason", reason);
    return exchange.getResponse().setComplete();
  }
}
