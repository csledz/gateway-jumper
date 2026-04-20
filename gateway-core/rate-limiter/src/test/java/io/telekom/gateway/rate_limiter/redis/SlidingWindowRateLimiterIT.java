// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.rate_limiter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.rate_limiter.api.RateLimitDecision;
import io.telekom.gateway.rate_limiter.api.RateLimitKey;
import io.telekom.gateway.rate_limiter.api.RateLimitPolicy;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

/**
 * Integration tests for {@link SlidingWindowRateLimiter}.
 *
 * <p>Scenarios covered:
 *
 * <ul>
 *   <li>single-pod: admits exactly {@code limit + burst} requests inside a window,
 *   <li>multi-pod: two limiter instances sharing the same Redis still honor the global limit in
 *       aggregate (the MUST-HAVE stated in the module README).
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlidingWindowRateLimiterIT {

  private GenericContainer<?> redis;
  private LettuceConnectionFactory factory;

  @BeforeAll
  void setup() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
    RedisStandaloneConfiguration cfg =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
    factory = new LettuceConnectionFactory(cfg);
    factory.afterPropertiesSet();
  }

  @AfterAll
  void teardown() {
    if (factory != null) {
      factory.destroy();
    }
    if (redis != null) {
      redis.stop();
    }
  }

  @Test
  void singlePod_admitsExactlyLimitThenRejects() {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(factory, Clock.systemUTC());
    RateLimitPolicy policy = new RateLimitPolicy(5, 10, 0, null);
    RateLimitKey key = uniqueKey();

    AtomicInteger allowed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();

    Flux.range(0, 10)
        .concatMap(i -> limiter.check(key, policy))
        .doOnNext(d -> (d.allowed() ? allowed : rejected).incrementAndGet())
        .blockLast();

    assertThat(allowed.get()).isEqualTo(5);
    assertThat(rejected.get()).isEqualTo(5);
  }

  @Test
  void multiPod_aggregateLimitIsGlobalNotPerPod() {
    SlidingWindowRateLimiter podA = new SlidingWindowRateLimiter(factory, Clock.systemUTC());
    SlidingWindowRateLimiter podB = new SlidingWindowRateLimiter(factory, Clock.systemUTC());
    RateLimitPolicy policy = new RateLimitPolicy(20, 10, 0, null);
    RateLimitKey key = uniqueKey();

    AtomicInteger totalAllowed = new AtomicInteger();

    Flux.merge(
            Flux.range(0, 25)
                .concatMap(i -> podA.check(key, policy))
                .doOnNext(
                    d -> {
                      if (d.allowed()) totalAllowed.incrementAndGet();
                    }),
            Flux.range(0, 25)
                .concatMap(i -> podB.check(key, policy))
                .doOnNext(
                    d -> {
                      if (d.allowed()) totalAllowed.incrementAndGet();
                    }))
        .blockLast();

    // With a global limit of 20, two pods each pushing 25 should admit *in total* ≈ 20.
    // Allow ±2 to absorb script-boundary timing (the oldest entry may expire mid-run).
    assertThat(totalAllowed.get()).isBetween(20, 22);
  }

  @Test
  void differentKeysHaveIndependentBudgets() {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(factory, Clock.systemUTC());
    RateLimitPolicy policy = new RateLimitPolicy(3, 10, 0, null);
    RateLimitKey keyA = uniqueKey();
    RateLimitKey keyB = uniqueKey();

    for (int i = 0; i < 3; i++) {
      assertThat(limiter.check(keyA, policy).block())
          .extracting(RateLimitDecision::allowed)
          .isEqualTo(true);
    }
    assertThat(limiter.check(keyA, policy).block())
        .extracting(RateLimitDecision::allowed)
        .isEqualTo(false);

    // keyB budget is untouched.
    assertThat(limiter.check(keyB, policy).block())
        .extracting(RateLimitDecision::allowed)
        .isEqualTo(true);
  }

  private RateLimitKey uniqueKey() {
    String id = UUID.randomUUID().toString();
    return new RateLimitKey(RateLimitKey.Scope.CONSUMER, id, "/r", "/a");
  }
}
