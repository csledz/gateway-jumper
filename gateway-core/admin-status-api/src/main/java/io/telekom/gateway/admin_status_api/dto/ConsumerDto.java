// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.dto;

import java.util.List;

/** Public view of a consumer (client) registered at the gateway. */
public record ConsumerDto(String id, String displayName, List<String> scopes, String zone) {}
