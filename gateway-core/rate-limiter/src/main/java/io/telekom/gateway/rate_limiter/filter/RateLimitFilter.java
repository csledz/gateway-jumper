// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.rate_limiter.filter;

import io.telekom.gateway.rate_limiter.api.RateLimitDecision;
import io.telekom.gateway.rate_limiter.api.RateLimitKey;
import io.telekom.gateway.rate_limiter.api.RateLimitPolicy;
import io.telekom.gateway.rate_limiter.api.RateLimiter;
import io.telekom.gateway.rate_limiter.config.RateLimitProperties;
import java.util.Optional;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@link WebFilter} at order 300. Resolves the applicable policy + key for the exchange (via a
 * pluggable {@link BiFunction}), invokes the limiter, and either passes the request through or
 * short-circuits with 429 and informational rate-limit headers.
 */
@Slf4j
public class RateLimitFilter implements WebFilter, Ordered {

  public static final int ORDER = 300;

  private final RateLimiter limiter;
  private final BiFunction<ServerWebExchange, RateLimitProperties, Optional<KeyAndPolicy>> resolver;
  private final RateLimitProperties props;

  public RateLimitFilter(
      RateLimiter limiter,
      BiFunction<ServerWebExchange, RateLimitProperties, Optional<KeyAndPolicy>> resolver,
      RateLimitProperties props) {
    this.limiter = limiter;
    this.resolver = resolver;
    this.props = props;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    Optional<KeyAndPolicy> resolved = resolver.apply(exchange, props);
    if (resolved.isEmpty()) {
      return chain.filter(exchange);
    }
    KeyAndPolicy kp = resolved.get();
    return limiter
        .check(kp.key(), kp.policy())
        .flatMap(
            decision -> {
              writeHeaders(exchange, kp.policy(), decision);
              if (decision.allowed()) {
                return chain.filter(exchange);
              }
              return rejectTooManyRequests(exchange, decision);
            });
  }

  private void writeHeaders(
      ServerWebExchange exchange, RateLimitPolicy policy, RateLimitDecision decision) {
    var headers = exchange.getResponse().getHeaders();
    headers.set("X-RateLimit-Limit", Integer.toString(policy.limit() + policy.burst()));
    if (decision.remaining() >= 0) {
      headers.set("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
    }
    if (decision.resetAtEpochMs() > 0) {
      headers.set(
          "X-RateLimit-Reset", Long.toString(Math.max(0L, decision.resetAtEpochMs() / 1000L)));
    }
  }

  private Mono<Void> rejectTooManyRequests(ServerWebExchange exchange, RateLimitDecision decision) {
    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    long retrySeconds = Math.max(1L, (decision.retryAfterMillis() + 999) / 1000L);
    exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retrySeconds));
    log.debug("rate-limit rejection: retryAfter={}s", retrySeconds);
    return exchange.getResponse().setComplete();
  }

  /** Resolved (key, policy) pair for the current exchange. */
  public record KeyAndPolicy(RateLimitKey key, RateLimitPolicy policy) {}
}
