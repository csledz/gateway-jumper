// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.api;

import java.time.Duration;

/**
 * Circuit breaker configuration for a single route.
 *
 * @param failureRateThreshold percentage of failing calls above which the breaker opens (0-100)
 * @param slidingWindowSize number of calls counted in the rolling window
 * @param minimumNumberOfCalls minimum calls before the breaker can compute a failure rate
 * @param waitDurationInOpenState time the breaker stays OPEN before transitioning to HALF_OPEN
 * @param permittedCallsInHalfOpenState number of probe calls allowed in HALF_OPEN
 */
public record CbConfig(
    float failureRateThreshold,
    int slidingWindowSize,
    int minimumNumberOfCalls,
    Duration waitDurationInOpenState,
    int permittedCallsInHalfOpenState) {}
