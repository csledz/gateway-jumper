// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.api;

/**
 * Top-level per-route resilience policy. Any component (circuit breaker, bulkhead, retry) may be
 * {@code null} to disable it for a given route.
 *
 * @param cb circuit breaker configuration
 * @param bulkhead bulkhead configuration
 * @param retry retry configuration
 */
public record ResiliencePolicy(CbConfig cb, BhConfig bulkhead, RetryConfig retry) {}
