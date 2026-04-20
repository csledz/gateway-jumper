// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.rate_limiter.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.rate_limiter.api.RateLimitDecision;
import io.telekom.gateway.rate_limiter.api.RateLimitKey;
import io.telekom.gateway.rate_limiter.api.RateLimiter;
import io.telekom.gateway.rate_limiter.config.RateLimitProperties;
import io.telekom.gateway.rate_limiter.filter.RateLimitFilter.KeyAndPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RateLimitFilterTest {

  private final RateLimitProperties props = new RateLimitProperties(10, 1, 0, true);

  @Test
  void allowedRequestReceivesInformationalHeadersAndPassesChain() {
    RateLimiter limiter = (key, policy) -> Mono.just(new RateLimitDecision(true, 7, 100_000L, 0L));
    RateLimitFilter filter =
        new RateLimitFilter(
            limiter,
            (exchange, p) ->
                Optional.of(
                    new KeyAndPolicy(
                        new RateLimitKey(RateLimitKey.Scope.ROUTE, "id", "/r", "/r"),
                        p.defaultPolicy())),
            props);
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    boolean[] hit = new boolean[1];
    filter
        .filter(
            exchange,
            ex -> {
              hit[0] = true;
              return Mono.empty();
            })
        .block();

    assertThat(hit[0]).isTrue();
    assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
    assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
        .isEqualTo("7");
  }

  @Test
  void rejectedRequestReturns429WithRetryAfter() {
    RateLimiter limiter =
        (key, policy) -> Mono.just(new RateLimitDecision(false, 0, 100_000L, 2500L));
    RateLimitFilter filter =
        new RateLimitFilter(
            limiter,
            (exchange, p) ->
                Optional.of(
                    new KeyAndPolicy(
                        new RateLimitKey(RateLimitKey.Scope.ROUTE, "id", "/r", "/r"),
                        p.defaultPolicy())),
            props);
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    filter.filter(exchange, ex -> Mono.error(new AssertionError("should not hit chain"))).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
  }

  @Test
  void noResolvedPolicyShortCircuitsToChain() {
    RateLimitFilter filter =
        new RateLimitFilter(
            (key, policy) -> Mono.error(new AssertionError("limiter must not be called")),
            (exchange, p) -> Optional.empty(),
            props);
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    boolean[] hit = new boolean[1];
    filter
        .filter(
            exchange,
            ex -> {
              hit[0] = true;
              return Mono.empty();
            })
        .block();

    assertThat(hit[0]).isTrue();
  }
}
