// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.output;

import java.io.PrintWriter;
import java.util.List;

/**
 * Minimal output adapter contract. Implementations render either a collection (tables, lists) or a
 * single detail object. stdout only — callers never embed ANSI codes or progress messages here.
 */
public interface Printer {

  /**
   * Print a list of rows. {@code columns} is the ordered list of header labels; every map in {@code
   * rows} should expose those keys. Implementations are free to render extra fields (e.g. JSON may
   * dump the whole map while TABLE only renders listed columns).
   */
  void printList(
      PrintWriter out, List<String> columns, List<? extends java.util.Map<String, ?>> rows);

  /** Print a single detail object, typically produced by a {@code describe} command. */
  void printObject(PrintWriter out, Object object);

  /** Factory dispatching on {@link OutputFormat}. */
  static Printer of(OutputFormat format) {
    return switch (format) {
      case JSON -> new JsonPrinter();
      case YAML -> new YamlPrinter();
      case TABLE -> new TablePrinter();
    };
  }
}
