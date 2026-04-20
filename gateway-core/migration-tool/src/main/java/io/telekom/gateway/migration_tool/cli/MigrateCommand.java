// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.cli;

import io.telekom.gateway.migration_tool.emit.CrdYamlWriter;
import io.telekom.gateway.migration_tool.kong.DeckReader;
import io.telekom.gateway.migration_tool.mapping.KongToCrdMapper;
import io.telekom.gateway.migration_tool.mapping.UnmigratedReport;
import io.telekom.gateway.migration_tool.model.DeckConfig;
import io.telekom.gateway.migration_tool.model.MigrationResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Top-level CLI entry point for the gateway-core migration tool.
 *
 * <p>Subcommands:
 *
 * <ul>
 *   <li>{@code migrate} - read decK YAML, write CRDs, print a diff report
 *   <li>{@code diff} - only print the diff / unmigrated report; exit non-zero if anything is
 *       unmigrated
 *   <li>{@code validate} - parse the decK YAML and confirm it is readable
 * </ul>
 */
@Command(
    name = "gateway-migrate",
    mixinStandardHelpOptions = true,
    version = "gateway-migrate 0.0.0",
    description = "Migrate a Kong declarative (decK) YAML configuration to gateway-core CRDs.",
    subcommands = {
      MigrateCommand.Migrate.class,
      MigrateCommand.Diff.class,
      MigrateCommand.Validate.class,
    })
public final class MigrateCommand implements Runnable {

  @Override
  public void run() {
    // When invoked without a subcommand, print usage to stdout.
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    int exit = new CommandLine(new MigrateCommand()).execute(args);
    System.exit(exit);
  }

  // ------------------------------------------------------------------ migrate

  @Command(
      name = "migrate",
      mixinStandardHelpOptions = true,
      description = "Translate Kong decK YAML to gateway-core CRD YAMLs.")
  public static class Migrate implements Callable<Integer> {

    @Option(
        names = {"-i", "--input"},
        required = true,
        description = "Path to the Kong decK YAML file.")
    Path input;

    @Option(
        names = {"-o", "--output"},
        required = true,
        description = "Output directory for the generated CRD YAMLs.")
    Path output;

    @Option(
        names = {"--fail-on-unmigrated"},
        description = "Exit with code 2 if any Kong plugin could not be migrated.")
    boolean failOnUnmigrated;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = new PrintWriter(System.out, true);
      PrintWriter err = new PrintWriter(System.err, true);

      DeckConfig cfg = new DeckReader().read(input);
      MigrationResult result = new KongToCrdMapper().map(cfg);
      new CrdYamlWriter().writeAll(result.getResources(), output);

      out.printf(
          "Migrated %d resource(s) from %s to %s%n", result.getResources().size(), input, output);

      String report = UnmigratedReport.render(result);
      err.print(report);
      err.flush();

      if (failOnUnmigrated && !result.getUnmigrated().isEmpty()) {
        return 2;
      }
      return 0;
    }
  }

  // --------------------------------------------------------------------- diff

  @Command(
      name = "diff",
      mixinStandardHelpOptions = true,
      description =
          "Report Kong plugins that have no gateway-core mapping; exits non-zero when the list is"
              + " non-empty.")
  public static class Diff implements Callable<Integer> {

    @Option(
        names = {"-i", "--input"},
        required = true,
        description = "Path to the Kong decK YAML file.")
    Path input;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = new PrintWriter(System.out, true);

      DeckConfig cfg = new DeckReader().read(input);
      MigrationResult result = new KongToCrdMapper().map(cfg);

      out.print(UnmigratedReport.render(result));
      out.flush();
      return result.getUnmigrated().isEmpty() ? 0 : 2;
    }
  }

  // ----------------------------------------------------------------- validate

  @Command(
      name = "validate",
      mixinStandardHelpOptions = true,
      description = "Parse the decK YAML and confirm it is well-formed.")
  public static class Validate implements Callable<Integer> {

    @Option(
        names = {"-i", "--input"},
        required = true,
        description = "Path to the Kong decK YAML file.")
    Path input;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = new PrintWriter(System.out, true);
      PrintWriter err = new PrintWriter(System.err, true);

      try {
        DeckConfig cfg = new DeckReader().read(input);
        out.printf(
            "OK: %d service(s), %d consumer(s), %d global plugin(s)%n",
            cfg.getServices().size(), cfg.getConsumers().size(), cfg.getPlugins().size());
        return 0;
      } catch (Exception e) {
        err.println("Invalid decK YAML: " + e.getMessage());
        return 1;
      }
    }
  }
}
