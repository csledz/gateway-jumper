// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** A Kong plugin that the migration tool cannot translate, with a human-readable reason. */
@Data
@AllArgsConstructor
public class UnmigratedEntry {

  /** Kong plugin name (e.g. {@code prometheus}). */
  private String pluginName;

  /** Scope: global / service / route / consumer. */
  private String scope;

  /** Enclosing entity name (may be {@code null} for global). */
  private String scopeRef;

  /** Explanation shown in the diff report. */
  private String reason;
}
