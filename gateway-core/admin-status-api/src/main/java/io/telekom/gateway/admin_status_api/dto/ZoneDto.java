// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.dto;

/** Public view of a runtime zone. */
public record ZoneDto(String name, String environment, int routeCount) {}
