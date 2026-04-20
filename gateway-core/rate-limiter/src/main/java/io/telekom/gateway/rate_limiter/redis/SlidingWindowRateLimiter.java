// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.rate_limiter.redis;

import io.telekom.gateway.rate_limiter.api.RateLimitDecision;
import io.telekom.gateway.rate_limiter.api.RateLimitKey;
import io.telekom.gateway.rate_limiter.api.RateLimitPolicy;
import io.telekom.gateway.rate_limiter.api.RateLimiter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

/**
 * Sliding-window rate limiter backed by a Redis ZSET plus an atomic Lua script.
 *
 * <p>Correctness under multi-pod: the check-and-increment is a single {@code EVAL} so concurrent
 * pods cannot race. Spring Data's {@code RedisScript.execute} handles {@code EVALSHA} caching +
 * {@code EVAL} fallback on script-cache miss.
 *
 * <p>When Redis is unreachable the limiter emits a fail-open decision: request allowed,
 * remaining/reset unknown. Callers that need fail-closed behavior can wrap this in a filter-side
 * policy.
 */
@Slf4j
public class SlidingWindowRateLimiter implements RateLimiter {

  private final ReactiveStringRedisTemplate redis;
  private final DefaultRedisScript<List> script;
  private final Clock clock;

  public SlidingWindowRateLimiter(ReactiveRedisConnectionFactory factory, Clock clock) {
    this.redis = new ReactiveStringRedisTemplate(factory);
    this.clock = clock;
    DefaultRedisScript<List> s = new DefaultRedisScript<>();
    s.setScriptText(loadScript());
    s.setResultType(List.class);
    this.script = s;
  }

  @Override
  public Mono<RateLimitDecision> check(RateLimitKey key, RateLimitPolicy policy) {
    long now = clock.millis();
    long windowMs = policy.windowSeconds() * 1000L;
    String requestId = UUID.randomUUID().toString();
    return redis
        .execute(
            script,
            List.of(redisKey(key)),
            List.of(
                Long.toString(windowMs),
                Integer.toString(policy.limit()),
                Integer.toString(policy.burst()),
                Long.toString(now),
                requestId))
        .next()
        .map(this::toDecision)
        .onErrorResume(
            err -> {
              log.warn("rate-limit check failed, fail-open: {}", err.toString());
              return Mono.just(new RateLimitDecision(true, -1, -1L, 0L));
            });
  }

  @SuppressWarnings("unchecked")
  private RateLimitDecision toDecision(Object raw) {
    List<Object> parts = (List<Object>) raw;
    long allowed = asLong(parts.get(0));
    long remaining = asLong(parts.get(1));
    long resetAt = asLong(parts.get(2));
    long retryAfter = asLong(parts.get(3));
    return new RateLimitDecision(allowed == 1L, (int) remaining, resetAt, retryAfter);
  }

  private long asLong(Object o) {
    if (o instanceof Number n) {
      return n.longValue();
    }
    if (o instanceof String s) {
      return Long.parseLong(s);
    }
    throw new IllegalStateException("unexpected Redis script return type: " + o.getClass());
  }

  private String redisKey(RateLimitKey key) {
    return "gateway:ratelimit:"
        + key.scope()
        + ":"
        + safe(key.id())
        + ":"
        + safe(key.route())
        + ":"
        + safe(key.api());
  }

  private String safe(String s) {
    return s == null ? "_" : s.replace(":", "_");
  }

  private String loadScript() {
    try (InputStream in = new ClassPathResource("redis/sliding-window.lua").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("sliding-window.lua missing from classpath", e);
    }
  }
}
