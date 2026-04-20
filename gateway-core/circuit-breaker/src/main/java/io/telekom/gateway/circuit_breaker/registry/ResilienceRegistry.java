// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.registry;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.telekom.gateway.circuit_breaker.api.BhConfig;
import io.telekom.gateway.circuit_breaker.api.CbConfig;
import io.telekom.gateway.circuit_breaker.api.ResiliencePolicy;
import io.telekom.gateway.circuit_breaker.api.RetryConfig;
import io.telekom.gateway.circuit_breaker.config.ResilienceDefaults;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-route registry for Resilience4j primitives. Caches {@link CircuitBreaker} and {@link
 * Bulkhead} instances by {@code routeId} and exposes them via Micrometer. Retry is modelled as a
 * plain configuration record because the gateway applies retry via reactor operators.
 */
@Slf4j
@Component
public class ResilienceRegistry {

  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final BulkheadRegistry bulkheadRegistry;
  private final Map<String, ResiliencePolicy> policies = new ConcurrentHashMap<>();

  public ResilienceRegistry(@Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
    this.circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    this.bulkheadRegistry = BulkheadRegistry.ofDefaults();
    if (meterRegistry != null) {
      TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry)
          .bindTo(meterRegistry);
      TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meterRegistry);
    }
  }

  /** Register or update the policy for a route. */
  public void register(String routeId, ResiliencePolicy policy) {
    policies.put(routeId, policy);
    if (policy.cb() != null) {
      circuitBreakerRegistry.replace(
          routeId, circuitBreakerRegistry.circuitBreaker(routeId, toCbConfig(policy.cb())));
    }
    if (policy.bulkhead() != null) {
      bulkheadRegistry.replace(
          routeId, bulkheadRegistry.bulkhead(routeId, toBhConfig(policy.bulkhead())));
    }
  }

  public ResiliencePolicy policyFor(String routeId) {
    return policies.getOrDefault(routeId, ResilienceDefaults.defaultPolicy());
  }

  public CircuitBreaker circuitBreaker(String routeId) {
    return circuitBreakerRegistry.circuitBreaker(
        routeId, () -> toCbConfig(policyFor(routeId).cb()));
  }

  public Bulkhead bulkhead(String routeId) {
    return bulkheadRegistry.bulkhead(routeId, () -> toBhConfig(policyFor(routeId).bulkhead()));
  }

  public RetryConfig retryConfig(String routeId) {
    RetryConfig cfg = policyFor(routeId).retry();
    return cfg != null ? cfg : ResilienceDefaults.defaultRetry();
  }

  private static CircuitBreakerConfig toCbConfig(@Nullable CbConfig cb) {
    CbConfig effective = cb != null ? cb : ResilienceDefaults.defaultCb();
    return CircuitBreakerConfig.custom()
        .failureRateThreshold(effective.failureRateThreshold())
        .slidingWindowSize(effective.slidingWindowSize())
        .minimumNumberOfCalls(effective.minimumNumberOfCalls())
        .waitDurationInOpenState(effective.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(effective.permittedCallsInHalfOpenState())
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        .build();
  }

  private static BulkheadConfig toBhConfig(@Nullable BhConfig bh) {
    BhConfig effective = bh != null ? bh : ResilienceDefaults.defaultBulkhead();
    return BulkheadConfig.custom()
        .maxConcurrentCalls(effective.maxConcurrentCalls())
        .maxWaitDuration(effective.maxWaitDuration())
        .build();
  }
}
