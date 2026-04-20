// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.dto;

import java.time.Instant;

/** Snapshot of a zone's aggregated health. */
public record ZoneHealthDto(
    String zone, String status, int healthyRoutes, int totalRoutes, Instant observedAt) {}
