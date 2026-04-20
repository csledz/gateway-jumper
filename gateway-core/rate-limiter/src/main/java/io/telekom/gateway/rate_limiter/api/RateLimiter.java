// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.rate_limiter.api;

import reactor.core.publisher.Mono;

/** Public contract for the rate limiter. See README for semantics. */
public interface RateLimiter {

  /** Evaluate a single request against the given policy. */
  Mono<RateLimitDecision> check(RateLimitKey key, RateLimitPolicy policy);
}
