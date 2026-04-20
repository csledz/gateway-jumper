// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Helper that resolves the root-level {@code --context}/{@code --namespace}/{@code --admin-url}
 * flags from any subcommand's {@link CommandSpec}. Picocli's {@code @Option(scope = INHERIT)} makes
 * them visible on every subcommand, but the option <em>values</em> live on the root; this walks up
 * the command tree and reads them through picocli's own API — no reflection.
 */
public final class ParentOptions {

  private ParentOptions() {}

  public static String kubeContext(CommandSpec spec) {
    return readString(spec, "--context");
  }

  public static String namespace(CommandSpec spec) {
    return readString(spec, "-n");
  }

  public static String adminUrl(CommandSpec spec) {
    String raw = readString(spec, "--admin-url");
    if (raw != null && !raw.isBlank()) {
      return raw;
    }
    String env = System.getenv("GATECTL_ADMIN_URL");
    return env == null || env.isBlank() ? null : env;
  }

  private static String readString(CommandSpec spec, String optionName) {
    CommandSpec root = spec;
    while (root.parent() != null) {
      root = root.parent();
    }
    OptionSpec opt = root.findOption(optionName);
    if (opt == null) {
      throw new CommandLine.ParameterException(
          root.commandLine(), "Internal error: root option " + optionName + " not registered");
    }
    return opt.getValue();
  }
}
