// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.rate_limiter.api;

/** Outcome of a check: allowed flag, remaining quota, reset instant, retry-after hint. */
public record RateLimitDecision(
    boolean allowed, int remaining, long resetAtEpochMs, long retryAfterMillis) {}
