// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Public view of a runtime route. Intentionally flat and stable — internal CRD types must not leak.
 */
public record RouteDto(
    String id,
    String zone,
    String upstream,
    List<String> predicates,
    List<String> filters,
    Map<String, String> labels,
    Instant lastUpdated) {}
