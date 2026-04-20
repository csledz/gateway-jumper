// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.rate_limiter.api;

/**
 * Configured policy for a given key: sliding-window limit, window length, burst, key expression.
 */
public record RateLimitPolicy(int limit, int windowSeconds, int burst, String keyExpression) {}
