// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.describe;

import java.io.PrintWriter;
import java.util.Map;

/**
 * Shared rendering for {@code describe} subcommands: kubectl-style indented key/value blocks. Map
 * values are rendered on child lines with two-space indent.
 */
final class DescribeRenderer {

  private DescribeRenderer() {}

  static void render(PrintWriter out, Map<String, Object> view) {
    render(out, view, 0);
    out.flush();
  }

  private static void render(PrintWriter out, Object value, int depth) {
    String indent = "  ".repeat(depth);
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> e : map.entrySet()) {
        Object v = e.getValue();
        if (v instanceof Map<?, ?> nested && !nested.isEmpty()) {
          out.printf("%s%s:%n", indent, e.getKey());
          render(out, v, depth + 1);
        } else if (v instanceof Iterable<?> it && it.iterator().hasNext()) {
          out.printf("%s%s:%n", indent, e.getKey());
          for (Object item : it) {
            if (item instanceof Map<?, ?>) {
              render(out, item, depth + 1);
              out.println();
            } else {
              out.printf("%s  - %s%n", indent, item);
            }
          }
        } else {
          out.printf("%s%s: %s%n", indent, e.getKey(), renderScalar(v));
        }
      }
    } else {
      out.printf("%s%s%n", indent, renderScalar(value));
    }
  }

  private static String renderScalar(Object value) {
    if (value == null) {
      return "<none>";
    }
    if (value instanceof Map<?, ?> m && m.isEmpty()) {
      return "{}";
    }
    if (value instanceof Iterable<?> it && !it.iterator().hasNext()) {
      return "[]";
    }
    return String.valueOf(value);
  }
}
