// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.output;

import java.util.Locale;

/** Output formats supported by {@code get}/{@code describe}/{@code health} commands. */
public enum OutputFormat {
  TABLE,
  JSON,
  YAML;

  /** Lenient parse so users can pass {@code -o json} in any case. */
  public static OutputFormat parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return TABLE;
    }
    return OutputFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
