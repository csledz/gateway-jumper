// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.api;

import java.time.Duration;

/**
 * Retry configuration for a single route. Applies only to idempotent HTTP methods (GET, HEAD,
 * OPTIONS) unless {@code retryNonIdempotent} is enabled.
 *
 * @param maxAttempts total attempts including the original call
 * @param initialBackoff initial backoff before the first retry
 * @param backoffMultiplier exponential multiplier applied per retry (delay = initial *
 *     multiplier^n)
 * @param retryOnStatuses HTTP statuses considered transient and retried
 * @param retryNonIdempotent when true, retry is applied even for non-idempotent methods (e.g. POST)
 */
public record RetryConfig(
    int maxAttempts,
    Duration initialBackoff,
    double backoffMultiplier,
    int[] retryOnStatuses,
    boolean retryNonIdempotent) {}
