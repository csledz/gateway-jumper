// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.List;
import java.util.Map;

/** Shared CLI runner + stdout/stderr assertions for all feature files. */
public class CliSteps {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @After
  public void tearDown() {
    World.reset();
  }

  @When("I run {string}")
  public void iRun(String args) {
    CliDriver.exec(World.current(), args);
  }

  @Then("the exit code is {int}")
  public void theExitCodeIs(int expected) {
    World w = World.current();
    assertThat(w.exitCode)
        .as("expected exit %d, stdout=%s stderr=%s", expected, w.stdout, w.stderr)
        .isEqualTo(expected);
  }

  @Then("stdout contains {string}")
  public void stdoutContains(String needle) {
    assertThat(World.current().stdout).contains(needle);
  }

  @Then("stderr contains {string}")
  public void stderrContains(String needle) {
    assertThat(World.current().stderr).contains(needle);
  }

  @Then("stdout is valid JSON")
  public void stdoutIsValidJson() {
    try {
      MAPPER.readTree(World.current().stdout);
    } catch (Exception e) {
      throw new AssertionError("stdout is not valid JSON: " + World.current().stdout, e);
    }
  }

  @Then("stdout JSON has {int} entries")
  public void stdoutJsonHas(int expected) throws Exception {
    List<Map<String, Object>> entries =
        MAPPER.readValue(World.current().stdout, new TypeReference<List<Map<String, Object>>>() {});
    assertThat(entries).hasSize(expected);
  }
}
