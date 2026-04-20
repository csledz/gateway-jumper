// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.get;

import io.telekom.gateway.cli.output.OutputFormat;
import picocli.CommandLine.Option;

/** Reusable {@code -o/--output} option shared by every {@code get}/{@code describe} subcommand. */
public final class OutputOption {

  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json, yaml.",
      defaultValue = "table",
      paramLabel = "FORMAT")
  private String outputRaw;

  public OutputFormat format() {
    return OutputFormat.parse(outputRaw);
  }
}
