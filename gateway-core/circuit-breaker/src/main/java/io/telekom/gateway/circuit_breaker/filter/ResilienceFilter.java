// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.filter;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.telekom.gateway.circuit_breaker.api.RetryConfig;
import io.telekom.gateway.circuit_breaker.registry.ResilienceRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Global filter that applies per-route Resilience4j primitives (circuit breaker, bulkhead, retry)
 * to the reactive filter chain. Short-circuits with an appropriate HTTP status when a resilience
 * guard trips.
 *
 * <ul>
 *   <li>Circuit open &rarr; {@code 503 Service Unavailable}
 *   <li>Bulkhead full &rarr; {@code 429 Too Many Requests}
 *   <li>Retries exhausted &rarr; {@code 502 Bad Gateway}
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResilienceFilter implements GlobalFilter, Ordered {

  /** Gateway filter order; 700 places this filter in the CIRCUIT_BREAKER slot. */
  public static final int CIRCUIT_BREAKER = 700;

  private static final Set<HttpMethod> IDEMPOTENT =
      Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

  private final ResilienceRegistry registry;
  private final ConcurrentMap<RetryConfig, Set<Integer>> retryableStatusCache =
      new ConcurrentHashMap<>();

  @Override
  public int getOrder() {
    return CIRCUIT_BREAKER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String routeId = routeId(exchange);
    CircuitBreaker cb = registry.circuitBreaker(routeId);
    Bulkhead bh = registry.bulkhead(routeId);
    RetryConfig retryConfig = registry.retryConfig(routeId);

    Mono<Void> base =
        Mono.defer(
            () -> chain.filter(exchange).then(Mono.defer(() -> failIfUpstream5xx(exchange))));
    Mono<Void> withRetry = applyRetry(exchange, base, retryConfig);
    Mono<Void> withCb = withRetry.transformDeferred(CircuitBreakerOperator.of(cb));
    Mono<Void> withBulkhead = withCb.transformDeferred(BulkheadOperator.of(bh));

    return withBulkhead.onErrorResume(ex -> handleError(exchange, ex));
  }

  private Mono<Void> failIfUpstream5xx(ServerWebExchange exchange) {
    HttpStatusCode status = exchange.getResponse().getStatusCode();
    if (status != null && status.is5xxServerError()) {
      return Mono.error(new TransientDownstreamException(status.value()));
    }
    return Mono.empty();
  }

  private Mono<Void> applyRetry(
      ServerWebExchange exchange, Mono<Void> base, RetryConfig retryConfig) {
    if (retryConfig == null || retryConfig.maxAttempts() <= 0) {
      return base;
    }
    boolean idempotent = IDEMPOTENT.contains(exchange.getRequest().getMethod());
    if (!idempotent && !retryConfig.retryNonIdempotent()) {
      return base;
    }
    Set<Integer> retryable = retryableStatuses(retryConfig);

    return base.retryWhen(
        Retry.backoff(retryConfig.maxAttempts(), retryConfig.initialBackoff())
            .maxBackoff(Duration.ofSeconds(10))
            .jitter(0d)
            .filter(
                throwable -> {
                  if (throwable instanceof TransientDownstreamException t) {
                    boolean match = retryable.contains(t.status());
                    if (match) {
                      resetResponseForRetry(exchange);
                    }
                    return match;
                  }
                  // Connection-style errors are always retried for idempotent methods.
                  return true;
                })
            .onRetryExhaustedThrow((spec, rs) -> new RetriesExhaustedException(rs.failure())));
  }

  private Set<Integer> retryableStatuses(RetryConfig retryConfig) {
    return retryableStatusCache.computeIfAbsent(
        retryConfig,
        cfg ->
            Arrays.stream(cfg.retryOnStatuses() == null ? new int[0] : cfg.retryOnStatuses())
                .boxed()
                .collect(Collectors.toUnmodifiableSet()));
  }

  private void resetResponseForRetry(ServerWebExchange exchange) {
    // Response has not been committed yet (upstream body is streamed only on success path).
    // Clear the provisional status and the "already routed" marker so NettyRoutingFilter
    // re-invokes the downstream on the next attempt.
    if (!exchange.getResponse().isCommitted()) {
      exchange.getResponse().setStatusCode(null);
      exchange.getResponse().getHeaders().clear();
      exchange.getAttributes().remove(ServerWebExchangeUtils.GATEWAY_ALREADY_ROUTED_ATTR);
      exchange.getAttributes().remove(ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR);
      exchange.getAttributes().remove(ServerWebExchangeUtils.CLIENT_RESPONSE_CONN_ATTR);
    }
  }

  private Mono<Void> handleError(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(ex);
    }
    HttpStatus status;
    if (ex instanceof CallNotPermittedException) {
      status = HttpStatus.SERVICE_UNAVAILABLE;
      log.warn("Circuit breaker open for route; returning 503");
    } else if (ex instanceof BulkheadFullException) {
      status = HttpStatus.TOO_MANY_REQUESTS;
      log.warn("Bulkhead full for route; returning 429");
    } else if (ex instanceof RetriesExhaustedException) {
      status = HttpStatus.BAD_GATEWAY;
      log.warn("Retries exhausted for route; returning 502", ex.getCause());
    } else if (ex instanceof TransientDownstreamException t) {
      // No retry configured/applicable; forward original upstream status.
      exchange.getResponse().setRawStatusCode(t.status());
      return exchange.getResponse().setComplete();
    } else {
      return Mono.error(ex);
    }
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }

  private static String routeId(ServerWebExchange exchange) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    return route != null ? route.getId() : "default";
  }

  /** Thrown when downstream emits a 5xx response so circuit breaker/retry observe a failure. */
  static final class TransientDownstreamException extends RuntimeException {
    private final int status;

    TransientDownstreamException(int status) {
      super("downstream returned HTTP " + status);
      this.status = status;
    }

    int status() {
      return status;
    }
  }

  /** Thrown when retry budget is exhausted so the outer handler can map to 502. */
  static final class RetriesExhaustedException extends RuntimeException {
    RetriesExhaustedException(Throwable cause) {
      super("retries exhausted", cause);
    }
  }
}
