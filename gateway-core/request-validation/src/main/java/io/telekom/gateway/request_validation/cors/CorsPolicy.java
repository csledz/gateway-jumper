// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.cors;

/** Immutable CORS policy configuration for a single route or default scope. */
public record CorsPolicy(
    java.util.List<String> allowedOrigins,
    java.util.List<String> allowedMethods,
    java.util.List<String> allowedHeaders,
    java.util.List<String> exposedHeaders,
    int maxAgeSeconds,
    boolean allowCredentials) {}
