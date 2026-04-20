// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.output;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * kubectl-style padded-column table writer. Columns are uppercased and left-aligned; each column's
 * width is the max of its header and values, padded with two spaces.
 */
public final class TablePrinter implements Printer {

  @Override
  public void printList(
      PrintWriter out, List<String> columns, List<? extends Map<String, ?>> rows) {
    if (columns == null || columns.isEmpty()) {
      return;
    }

    List<String> headers = new ArrayList<>(columns.size());
    int[] widths = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      String h = columns.get(i).toUpperCase(Locale.ROOT);
      headers.add(h);
      widths[i] = h.length();
    }

    List<List<String>> renderedRows = new ArrayList<>();
    if (rows != null) {
      for (Map<String, ?> row : rows) {
        List<String> renderedRow = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
          Object v = row.get(columns.get(i));
          String s = v == null ? "<none>" : String.valueOf(v);
          renderedRow.add(s);
          widths[i] = Math.max(widths[i], s.length());
        }
        renderedRows.add(renderedRow);
      }
    }

    out.println(renderRow(headers, widths));
    for (List<String> row : renderedRows) {
      out.println(renderRow(row, widths));
    }
    out.flush();
  }

  @Override
  public void printObject(PrintWriter out, Object object) {
    if (object == null) {
      out.println("<none>");
      out.flush();
      return;
    }
    if (object instanceof Map<?, ?> map) {
      int keyWidth = 0;
      for (Object k : map.keySet()) {
        keyWidth = Math.max(keyWidth, String.valueOf(k).length());
      }
      for (Map.Entry<?, ?> e : map.entrySet()) {
        out.printf(
            "%-" + (keyWidth + 2) + "s%s%n",
            String.valueOf(e.getKey()) + ":",
            renderValue(e.getValue()));
      }
    } else {
      out.println(object);
    }
    out.flush();
  }

  private static String renderValue(Object value) {
    if (value == null) {
      return "<none>";
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        return "[]";
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(list.get(i));
      }
      return sb.toString();
    }
    return String.valueOf(value);
  }

  private static String renderRow(List<String> cells, int[] widths) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      String cell = cells.get(i);
      sb.append(cell);
      if (i < cells.size() - 1) {
        int pad = widths[i] - cell.length() + 2;
        for (int p = 0; p < pad; p++) {
          sb.append(' ');
        }
      }
    }
    return sb.toString();
  }
}
