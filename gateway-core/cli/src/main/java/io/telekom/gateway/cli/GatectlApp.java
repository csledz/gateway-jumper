// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli;

import io.telekom.gateway.cli.cmd.describe.DescribeConsumer;
import io.telekom.gateway.cli.cmd.describe.DescribeRoute;
import io.telekom.gateway.cli.cmd.describe.DescribeZone;
import io.telekom.gateway.cli.cmd.get.GetConsumers;
import io.telekom.gateway.cli.cmd.get.GetMeshPeers;
import io.telekom.gateway.cli.cmd.get.GetRoutes;
import io.telekom.gateway.cli.cmd.get.GetZones;
import io.telekom.gateway.cli.cmd.health.HealthCheck;
import io.telekom.gateway.cli.cmd.logs.LogsTail;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code gatectl} — the operator CLI for gateway-core.
 *
 * <p>Subcommands are kubectl-style: {@code get}, {@code describe}, {@code logs}, {@code health}.
 * stdout is reserved for command output; all diagnostic/progress messages go to stderr. Exit codes
 * follow a strict contract: {@code 0} success, {@code 1} command error (runtime failure reaching
 * k8s or the admin API), {@code 2} usage error (bad flags).
 */
@Command(
    name = "gatectl",
    mixinStandardHelpOptions = true,
    versionProvider = GatectlApp.VersionProvider.class,
    description = "Operator CLI for the Kong-free gateway-core.",
    subcommands = {
      GetRoutes.class,
      GetZones.class,
      GetConsumers.class,
      GetMeshPeers.class,
      DescribeRoute.class,
      DescribeZone.class,
      DescribeConsumer.class,
      LogsTail.class,
      HealthCheck.class,
      CommandLine.HelpCommand.class,
    })
public final class GatectlApp implements Runnable {

  /** picocli exit code: usage error (bad flags / unknown subcommand). */
  public static final int EXIT_USAGE = 2;

  /** picocli exit code: command error (runtime failure). */
  public static final int EXIT_ERROR = 1;

  /** picocli exit code: success. */
  public static final int EXIT_OK = 0;

  @Option(
      names = {"--context"},
      description = "Kubeconfig context to use (default: current-context from ~/.kube/config).",
      scope = CommandLine.ScopeType.INHERIT)
  private String kubeContext;

  @Option(
      names = {"-n", "--namespace"},
      description = "Kubernetes namespace (default: ${DEFAULT-VALUE}).",
      scope = CommandLine.ScopeType.INHERIT,
      defaultValue = "gateway-system")
  private String namespace;

  @Option(
      names = {"--admin-url"},
      description =
          "Admin status API base URL. Overrides GATECTL_ADMIN_URL env var. "
              + "Default: http://localhost:8080",
      scope = CommandLine.ScopeType.INHERIT)
  private String adminUrl;

  @Spec private CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    // No default action — reject with a usage error so callers distinguish "nothing asked"
    // from "command succeeded". The ParameterExceptionHandler prints usage.
    throw new CommandLine.ParameterException(spec.commandLine(), "Specify a subcommand.");
  }

  /**
   * Entry point. Separates stdout (command data) from stderr (logs, usage, errors) and maps
   * picocli's execution result to our documented exit-code contract.
   */
  public static void main(String[] args) {
    int exit = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
    System.exit(exit);
  }

  /** Testable entry point: run the CLI with a fixed args array and fresh writers. */
  public static int run(String[] args, PrintWriter out, PrintWriter err) {
    CommandLine cli =
        new CommandLine(new GatectlApp())
            .setOut(out)
            .setErr(err)
            .setExecutionExceptionHandler(
                (ex, commandLine, parseResult) -> {
                  err.println("gatectl: " + ex.getMessage());
                  return EXIT_ERROR;
                })
            .setParameterExceptionHandler(
                (ex, params) -> {
                  CommandLine cmd = ex.getCommandLine();
                  cmd.getErr().println("gatectl: " + ex.getMessage());
                  CommandLine.UnmatchedArgumentException.printSuggestions(ex, cmd.getErr());
                  cmd.usage(cmd.getErr());
                  return EXIT_USAGE;
                });
    return cli.execute(args);
  }

  /** Version string surfaced via {@code gatectl --version}. */
  public static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String v = GatectlApp.class.getPackage().getImplementationVersion();
      return new String[] {"gatectl " + (v == null ? "0.0.0-dev" : v)};
    }
  }
}
