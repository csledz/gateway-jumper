// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.rate_limiter.config;

import io.telekom.gateway.rate_limiter.api.RateLimitKey;
import io.telekom.gateway.rate_limiter.api.RateLimiter;
import io.telekom.gateway.rate_limiter.filter.RateLimitFilter;
import io.telekom.gateway.rate_limiter.filter.RateLimitFilter.KeyAndPolicy;
import io.telekom.gateway.rate_limiter.redis.SlidingWindowRateLimiter;
import java.time.Clock;
import java.util.Optional;
import java.util.function.BiFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.web.server.ServerWebExchange;

/** Wires the rate-limiter beans. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiterConfig {

  @Bean
  @ConditionalOnMissingBean
  public Clock rateLimiterClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ReactiveRedisConnectionFactory.class)
  public RateLimiter slidingWindowRateLimiter(ReactiveRedisConnectionFactory factory, Clock clock) {
    return new SlidingWindowRateLimiter(factory, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public BiFunction<ServerWebExchange, RateLimitProperties, Optional<KeyAndPolicy>>
      rateLimitResolver() {
    return (exchange, props) ->
        Optional.of(
            new KeyAndPolicy(
                new RateLimitKey(
                    RateLimitKey.Scope.ROUTE,
                    exchange.getRequest().getRemoteAddress() == null
                        ? "anonymous"
                        : exchange.getRequest().getRemoteAddress().getHostString(),
                    exchange.getRequest().getPath().toString(),
                    exchange.getRequest().getPath().toString()),
                props.defaultPolicy()));
  }

  @Bean
  @ConditionalOnBean(RateLimiter.class)
  public RateLimitFilter rateLimitFilter(
      RateLimiter limiter,
      BiFunction<ServerWebExchange, RateLimitProperties, Optional<KeyAndPolicy>> resolver,
      RateLimitProperties props) {
    return new RateLimitFilter(limiter, resolver, props);
  }
}
