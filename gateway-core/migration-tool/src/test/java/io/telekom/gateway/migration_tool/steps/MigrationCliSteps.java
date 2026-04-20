// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.migration_tool.cli.MigrateCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine;

/** Cucumber step definitions for the migration CLI. */
public class MigrationCliSteps {

  private Path fixture;
  private Path outputDir;
  private int exitCode;
  private String stdout;
  private String stderr;

  // Cache of emitted resources, populated lazily and invalidated on each CLI run.
  private List<Map<String, Object>> cachedResources;

  @Before
  public void reset() throws Exception {
    this.fixture = null;
    this.outputDir = Files.createTempDirectory("gwmig-out-");
    this.exitCode = -1;
    this.stdout = "";
    this.stderr = "";
    this.cachedResources = null;
  }

  @Given("the Kong decK fixture {string}")
  public void theKongDeckFixture(String name) {
    Path p = Paths.get("src/test/resources/deck-fixtures").resolve(name).toAbsolutePath();
    assertThat(p).as("fixture must exist").exists();
    this.fixture = p;
  }

  @When("I run {string}")
  public void iRun(String subcommand) {
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    PrintStream prevOut = System.out;
    PrintStream prevErr = System.err;
    System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    try {
      String[] args =
          switch (subcommand) {
            case "migrate" ->
                new String[] {"migrate", "-i", fixture.toString(), "-o", outputDir.toString()};
            case "diff" -> new String[] {"diff", "-i", fixture.toString()};
            case "validate" -> new String[] {"validate", "-i", fixture.toString()};
            default -> throw new IllegalArgumentException("unknown subcommand: " + subcommand);
          };
      this.exitCode = new CommandLine(new MigrateCommand()).execute(args);
    } finally {
      System.setOut(prevOut);
      System.setErr(prevErr);
    }
    this.stdout = outBytes.toString(StandardCharsets.UTF_8);
    this.stderr = errBytes.toString(StandardCharsets.UTF_8);
    this.cachedResources = null;
  }

  @Then("the CLI exit code is {int}")
  public void theCliExitCodeIs(int expected) {
    assertThat(exitCode).as("exit code (stdout=%s, stderr=%s)", stdout, stderr).isEqualTo(expected);
  }

  @And("the stdout contains {string}")
  public void theStdoutContains(String needle) {
    assertThat(stdout).contains(needle);
  }

  @And("a {string} resource named {string} is emitted")
  public void aResourceNamedIsEmitted(String kind, String name) throws Exception {
    assertThat(findResource(kind, name))
        .as("expected %s/%s in output dir %s", kind, name, outputDir)
        .isNotNull();
  }

  @And("the emitted {string} {string} has upstream host {string}")
  public void upstreamHost(String kind, String name, String host) throws Exception {
    Map<String, Object> r = requireResource(kind, name);
    Map<?, ?> spec = (Map<?, ?>) r.get("spec");
    Map<?, ?> up = (Map<?, ?>) spec.get("upstream");
    assertThat(up.get("host")).isEqualTo(host);
  }

  @And("the emitted {string} {string} matches path {string}")
  public void matchesPath(String kind, String name, String path) throws Exception {
    Map<String, Object> r = requireResource(kind, name);
    Map<?, ?> spec = (Map<?, ?>) r.get("spec");
    Map<?, ?> match = (Map<?, ?>) spec.get("match");
    assertThat(match.get("paths"))
        .isInstanceOfSatisfying(List.class, l -> assertThat(l).contains(path));
  }

  @And("a GatewayCredential of type {string} for consumer {string} is emitted")
  public void gatewayCredentialOfType(String type, String consumer) throws Exception {
    Map<String, Object> match = null;
    for (Map<String, Object> r : readAllResources()) {
      if (!"GatewayCredential".equals(r.get("kind"))) continue;
      Map<?, ?> spec = (Map<?, ?>) r.get("spec");
      if (type.equals(spec.get("type")) && consumer.equals(spec.get("consumerRef"))) {
        match = r;
        break;
      }
    }
    assertThat(match)
        .as("expected GatewayCredential type=%s consumerRef=%s", type, consumer)
        .isNotNull();
  }

  @And("a GatewayPolicy of type {string} is emitted")
  public void gatewayPolicyOfType(String type) throws Exception {
    Map<String, Object> match = null;
    for (Map<String, Object> r : readAllResources()) {
      if (!"GatewayPolicy".equals(r.get("kind"))) continue;
      Map<?, ?> spec = (Map<?, ?>) r.get("spec");
      if (type.equals(spec.get("type"))) {
        match = r;
        break;
      }
    }
    assertThat(match).as("expected GatewayPolicy type=%s", type).isNotNull();
  }

  // --- helpers ---------------------------------------------------------

  private Map<String, Object> findResource(String kind, String name) throws Exception {
    for (Map<String, Object> r : readAllResources()) {
      if (kind.equals(r.get("kind"))) {
        Map<?, ?> md = (Map<?, ?>) r.get("metadata");
        if (md != null && name.equals(md.get("name"))) {
          return r;
        }
      }
    }
    return null;
  }

  private Map<String, Object> requireResource(String kind, String name) throws Exception {
    Map<String, Object> r = findResource(kind, name);
    assertThat(r).as("missing %s/%s", kind, name).isNotNull();
    return r;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> readAllResources() throws Exception {
    if (cachedResources != null) {
      return cachedResources;
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    java.util.ArrayList<Map<String, Object>> all = new java.util.ArrayList<>();
    try (var stream = Files.list(outputDir)) {
      for (Path file : stream.sorted().toList()) {
        if (!file.getFileName().toString().endsWith(".yaml")) continue;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
          for (Object doc : yaml.loadAll(reader)) {
            if (doc instanceof Map<?, ?> m) {
              all.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
          }
        }
      }
    }
    cachedResources = all;
    return all;
  }
}
