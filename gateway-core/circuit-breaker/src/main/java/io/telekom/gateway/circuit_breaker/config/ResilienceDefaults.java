// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.config;

import io.telekom.gateway.circuit_breaker.api.BhConfig;
import io.telekom.gateway.circuit_breaker.api.CbConfig;
import io.telekom.gateway.circuit_breaker.api.ResiliencePolicy;
import io.telekom.gateway.circuit_breaker.api.RetryConfig;
import java.time.Duration;

/** Sensible defaults used when a route does not provide its own {@link ResiliencePolicy}. */
public final class ResilienceDefaults {

  public static final float FAILURE_RATE_THRESHOLD = 50f;
  public static final int SLIDING_WINDOW_SIZE = 10;
  public static final int MINIMUM_NUMBER_OF_CALLS = 10;
  public static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);
  public static final int PERMITTED_CALLS_IN_HALF_OPEN = 3;

  public static final int MAX_CONCURRENT_CALLS = 64;
  public static final Duration BULKHEAD_MAX_WAIT = Duration.ZERO;

  public static final int MAX_RETRY_ATTEMPTS = 2;
  public static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
  public static final double BACKOFF_MULTIPLIER = 2.0d;
  public static final int[] RETRY_ON_STATUSES = {502, 503, 504};

  private ResilienceDefaults() {}

  public static CbConfig defaultCb() {
    return new CbConfig(
        FAILURE_RATE_THRESHOLD,
        SLIDING_WINDOW_SIZE,
        MINIMUM_NUMBER_OF_CALLS,
        WAIT_DURATION_IN_OPEN_STATE,
        PERMITTED_CALLS_IN_HALF_OPEN);
  }

  public static BhConfig defaultBulkhead() {
    return new BhConfig(MAX_CONCURRENT_CALLS, BULKHEAD_MAX_WAIT);
  }

  public static RetryConfig defaultRetry() {
    return new RetryConfig(
        MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF, BACKOFF_MULTIPLIER, RETRY_ON_STATUSES, false);
  }

  public static ResiliencePolicy defaultPolicy() {
    return new ResiliencePolicy(defaultCb(), defaultBulkhead(), defaultRetry());
  }
}
