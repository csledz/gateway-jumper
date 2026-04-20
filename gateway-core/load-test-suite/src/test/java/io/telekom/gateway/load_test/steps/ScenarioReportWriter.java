// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.load_test.steps;

import io.telekom.gateway.load_test.driver.LoadReport;
import io.telekom.gateway.load_test.scenario.Scenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes each scenario's {@link Scenario.ScenarioResult} out as a small Markdown file under the
 * configured reports directory ({@code load.reports.dir} system property, default {@code
 * target/load-reports}).
 */
@Slf4j
public final class ScenarioReportWriter {

  private final Path reportsDir;

  public ScenarioReportWriter() {
    String dir = System.getProperty("load.reports.dir", "target/load-reports");
    this.reportsDir = Path.of(dir);
  }

  public void write(String scenarioName, Scenario.ScenarioResult result) {
    try {
      Files.createDirectories(reportsDir);
      Path out = reportsDir.resolve(scenarioName + "-" + Instant.now().toEpochMilli() + ".md");
      Files.writeString(out, render(scenarioName, result), StandardCharsets.UTF_8);
      log.info("wrote scenario report: {}", out.toAbsolutePath());
    } catch (IOException e) {
      log.warn("could not write scenario report: {}", e.toString());
    }
  }

  private static String render(String name, Scenario.ScenarioResult r) {
    StringBuilder sb = new StringBuilder(2048);
    LoadReport lr = r.report();
    sb.append("# Scenario: ").append(name).append("\n\n");
    sb.append("Generated: ").append(Instant.now()).append("\n\n");
    sb.append("## Load report\n\n");
    sb.append("| metric | value |\n|---|---|\n");
    sb.append("| totalRequests | ").append(lr.totalRequests()).append(" |\n");
    sb.append("| status2xx | ").append(lr.status2xx()).append(" |\n");
    sb.append("| status4xx | ").append(lr.status4xx()).append(" |\n");
    sb.append("| status5xx | ").append(lr.status5xx()).append(" |\n");
    sb.append("| errorCount | ").append(lr.errorCount()).append(" |\n");
    sb.append("| p50 (ms) | ").append(lr.p50Ms()).append(" |\n");
    sb.append("| p95 (ms) | ").append(lr.p95Ms()).append(" |\n");
    sb.append("| p99 (ms) | ").append(lr.p99Ms()).append(" |\n");
    sb.append("| p999 (ms) | ").append(lr.p999Ms()).append(" |\n");
    sb.append("| max (ms) | ").append(lr.maxMs()).append(" |\n\n");
    sb.append("## Scenario observations\n\n");
    if (r.observations().isEmpty()) {
      sb.append("_(none)_\n");
    } else {
      for (Map.Entry<String, Object> e : r.observations().entrySet()) {
        sb.append("- **")
            .append(e.getKey())
            .append("**: ")
            .append(String.valueOf(e.getValue()))
            .append("\n");
      }
    }
    return sb.toString();
  }
}
