// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.output;

import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * Shared base for the JSON and YAML printers. Both formats differ only in the configured {@link
 * ObjectWriter} and a trailing newline: keeping the serialization flow here avoids copy-pasting
 * identical try/catch wrappers across two implementations.
 */
abstract class JacksonPrinter implements Printer {

  private final ObjectWriter writer;

  protected JacksonPrinter(ObjectWriter writer) {
    this.writer = writer;
  }

  @Override
  public final void printList(
      PrintWriter out, List<String> columns, List<? extends Map<String, ?>> rows) {
    emit(out, rows);
  }

  @Override
  public final void printObject(PrintWriter out, Object object) {
    emit(out, object);
  }

  private void emit(PrintWriter out, Object payload) {
    try {
      out.println(writer.writeValueAsString(payload));
      out.flush();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to render " + getClass().getSimpleName() + " output", e);
    }
  }
}
