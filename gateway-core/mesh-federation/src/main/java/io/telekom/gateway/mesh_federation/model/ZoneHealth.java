// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.model;

/**
 * Heartbeat snapshot for a zone.
 *
 * @param zone zone name being reported on
 * @param healthy whether the zone was observed healthy at the last report
 * @param lastSeenEpochMs last observation timestamp in epoch milliseconds
 * @param reportedBy name of the zone that emitted this record
 */
public record ZoneHealth(String zone, boolean healthy, long lastSeenEpochMs, String reportedBy) {}
