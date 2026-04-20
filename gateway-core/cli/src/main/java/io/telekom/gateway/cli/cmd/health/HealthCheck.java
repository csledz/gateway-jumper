// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.health;

import io.telekom.gateway.cli.admin.AdminClient;
import io.telekom.gateway.cli.admin.ZoneHealth;
import io.telekom.gateway.cli.cmd.ParentOptions;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import reactor.core.publisher.Flux;

/**
 * {@code gatectl health [ZONE…]} — hits {@code /admin/zones/{zone}/health} for each zone and prints
 * a colored table. With no zones the admin API's {@code /admin/zones} listing is queried first.
 *
 * <p>Exit code 1 if any zone reports anything other than {@code HEALTHY}.
 */
@Command(
    name = "health",
    description = "Check zone health against the admin-status-api.",
    mixinStandardHelpOptions = true)
public final class HealthCheck implements Callable<Integer> {

  static final Duration TIMEOUT = Duration.ofSeconds(5);

  static final String ANSI_RESET = "\u001B[0m";
  static final String ANSI_GREEN = "\u001B[32m";
  static final String ANSI_YELLOW = "\u001B[33m";
  static final String ANSI_RED = "\u001B[31m";
  static final String ANSI_BOLD = "\u001B[1m";

  @Parameters(
      paramLabel = "ZONE",
      description = "Zero or more zone names. Empty = discover via /admin/zones.",
      arity = "0..*")
  List<String> zones;

  @Option(
      names = {"--no-color"},
      description = "Disable ANSI colors (also implied when stdout is not a TTY).")
  boolean noColor;

  @Spec private CommandSpec spec;

  private final Function<String, AdminClient> adminClientFactory;

  public HealthCheck() {
    this(AdminClient::new);
  }

  public HealthCheck(Function<String, AdminClient> adminClientFactory) {
    this.adminClientFactory = adminClientFactory;
  }

  @Override
  public Integer call() {
    PrintWriter out = this.spec.commandLine().getOut();
    AdminClient admin = adminClientFactory.apply(ParentOptions.adminUrl(spec));

    List<String> targetZones = zones;
    if (targetZones == null || targetZones.isEmpty()) {
      targetZones =
          admin
              .listZones()
              .map(
                  list -> {
                    List<String> names = new ArrayList<>();
                    for (var entry : list) {
                      Object n = entry.get("name");
                      if (n != null) {
                        names.add(n.toString());
                      }
                    }
                    return names;
                  })
              .onErrorReturn(List.of())
              .block(TIMEOUT);
      if (targetZones == null) {
        targetZones = List.of();
      }
    }

    if (targetZones.isEmpty()) {
      this.spec.commandLine().getErr().println("No zones to check.");
      return 1;
    }

    List<ZoneHealth> results =
        Flux.fromIterable(targetZones)
            .flatMap(z -> admin.zoneHealth(z).timeout(TIMEOUT))
            .collectList()
            .block(TIMEOUT.multipliedBy(targetZones.size() + 1L));

    if (results == null) {
      results = List.of();
    }
    boolean allHealthy = renderTable(out, results, !noColor && System.console() != null);
    return allHealthy ? 0 : 1;
  }

  boolean renderTable(PrintWriter out, List<ZoneHealth> rows, boolean useColor) {
    int zoneWidth = "ZONE".length();
    int statusWidth = "STATUS".length();
    for (ZoneHealth h : rows) {
      zoneWidth = Math.max(zoneWidth, h.zone().length());
      statusWidth = Math.max(statusWidth, h.status().length());
    }
    out.printf(
        "%-" + (zoneWidth + 2) + "s%-" + (statusWidth + 2) + "s%s%n", "ZONE", "STATUS", "MESSAGE");
    boolean allHealthy = true;
    for (ZoneHealth h : rows) {
      // Pad with spaces based on the raw status width; render color after padding so alignment is
      // preserved even when ANSI escapes would otherwise inflate printf's %-Ns count.
      String rawStatus = h.status();
      int trailingSpaces = statusWidth - rawStatus.length() + 2;
      String paintedStatus = useColor ? colored(rawStatus) : rawStatus;
      out.printf(
          "%-" + (zoneWidth + 2) + "s%s%s%s%n",
          h.zone(),
          paintedStatus,
          " ".repeat(trailingSpaces),
          h.message());
      if (!isHealthy(rawStatus)) {
        allHealthy = false;
      }
    }
    out.flush();
    return allHealthy;
  }

  private static boolean isHealthy(String status) {
    return "HEALTHY".equals(status == null ? null : status.toUpperCase(Locale.ROOT));
  }

  private static String colored(String status) {
    String upper = status == null ? "" : status.toUpperCase(Locale.ROOT);
    return switch (upper) {
      case "HEALTHY" -> ANSI_GREEN + ANSI_BOLD + status + ANSI_RESET;
      case "DEGRADED" -> ANSI_YELLOW + ANSI_BOLD + status + ANSI_RESET;
      case "UNHEALTHY", "DOWN" -> ANSI_RED + ANSI_BOLD + status + ANSI_RESET;
      default -> ANSI_BOLD + status + ANSI_RESET;
    };
  }
}
