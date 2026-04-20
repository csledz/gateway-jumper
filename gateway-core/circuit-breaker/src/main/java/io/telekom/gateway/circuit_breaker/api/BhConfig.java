// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.api;

import java.time.Duration;

/**
 * Bulkhead configuration for a single route.
 *
 * @param maxConcurrentCalls maximum concurrent in-flight calls permitted through the bulkhead
 * @param maxWaitDuration time a call may wait for a permit before being rejected
 */
public record BhConfig(int maxConcurrentCalls, Duration maxWaitDuration) {}
