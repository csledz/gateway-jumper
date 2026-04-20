// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Aggregate output of a {@code KongToCrdMapper} run: the resources produced and the plugins that
 * had no mapping.
 */
@Data
public class MigrationResult {

  private final List<CrdResource> resources = new ArrayList<>();
  private final List<UnmigratedEntry> unmigrated = new ArrayList<>();
}
