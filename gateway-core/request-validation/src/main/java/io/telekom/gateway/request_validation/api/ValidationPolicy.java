// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.api;

import io.telekom.gateway.request_validation.cors.CorsPolicy;

/** Aggregate policy consumed by the request-validation pipeline stage. */
public record ValidationPolicy(
    CorsPolicy cors,
    String schemaRef,
    java.util.List<String> allowedContentTypes,
    long maxRequestBytes) {}
