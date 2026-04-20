// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.cors;

import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS filter at stage REQUEST_VALIDATION.
 *
 * <p>Preflight ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method})
 * requests are answered synchronously. Actual requests are forwarded; the response is decorated
 * before commit.
 */
@Slf4j
public class CorsFilter implements WebFilter, Ordered {

  public static final int ORDER = 110;
  private final PolicyLookup lookup;

  public CorsFilter(PolicyLookup lookup) {
    this.lookup = lookup;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    Optional<CorsPolicy> maybe =
        lookup.find(exchange).map(ValidationPolicy::cors).filter(p -> p != null);
    if (maybe.isEmpty()) {
      return chain.filter(exchange);
    }
    CorsPolicy policy = maybe.get();
    ServerHttpRequest request = exchange.getRequest();
    String origin = request.getHeaders().getOrigin();
    if (origin == null) {
      return chain.filter(exchange);
    }
    if (isPreflight(request)) {
      return handlePreflight(exchange, policy, origin);
    }
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              decorateActualResponse(exchange.getResponse(), policy, origin);
              return Mono.empty();
            });
    return chain.filter(exchange);
  }

  private boolean isPreflight(ServerHttpRequest request) {
    return HttpMethod.OPTIONS.equals(request.getMethod())
        && request.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
  }

  private Mono<Void> handlePreflight(ServerWebExchange exchange, CorsPolicy policy, String origin) {
    ServerHttpResponse response = exchange.getResponse();
    HttpHeaders req = exchange.getRequest().getHeaders();
    String originAllowed = originAllowed(policy, origin);
    if (originAllowed == null) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return response.setComplete();
    }
    String method = req.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
    if (method == null || !policy.allowedMethods().contains(method)) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return response.setComplete();
    }
    String requestedHeaders = req.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
    HttpHeaders out = response.getHeaders();
    out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, originAllowed);
    out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, method);
    if (policy.allowedHeaders() != null && !policy.allowedHeaders().isEmpty()) {
      out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, String.join(",", policy.allowedHeaders()));
    } else if (requestedHeaders != null) {
      out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, requestedHeaders);
    }
    if (policy.allowCredentials()) {
      out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }
    if (policy.maxAgeSeconds() > 0) {
      out.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, Integer.toString(policy.maxAgeSeconds()));
    }
    out.add(HttpHeaders.VARY, "Origin");
    response.setStatusCode(HttpStatus.NO_CONTENT);
    return response.setComplete();
  }

  private void decorateActualResponse(
      ServerHttpResponse response, CorsPolicy policy, String origin) {
    String allowed = originAllowed(policy, origin);
    if (allowed == null) {
      return;
    }
    HttpHeaders out = response.getHeaders();
    out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowed);
    if (policy.allowCredentials()) {
      out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }
    List<String> exposed = policy.exposedHeaders();
    if (exposed != null && !exposed.isEmpty()) {
      out.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(",", exposed));
    }
    out.add(HttpHeaders.VARY, "Origin");
  }

  /**
   * Returns the value to echo in {@code Access-Control-Allow-Origin}, or null if the origin is not
   * permitted. Wildcard only permitted when credentials are not required (per the Fetch standard).
   */
  private String originAllowed(CorsPolicy policy, String origin) {
    List<String> allowed = policy.allowedOrigins();
    if (allowed == null || allowed.isEmpty()) {
      return null;
    }
    if (allowed.contains("*") && !policy.allowCredentials()) {
      return "*";
    }
    return allowed.contains(origin) ? origin : null;
  }
}
