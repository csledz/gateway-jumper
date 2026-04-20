// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.dto;

import java.time.Instant;
import java.util.List;

/** Full read-only snapshot of the gateway's live state. */
public record SnapshotDto(
    Instant capturedAt,
    List<RouteDto> routes,
    List<ZoneDto> zones,
    List<ConsumerDto> consumers,
    CacheStatsDto cacheStats) {}
