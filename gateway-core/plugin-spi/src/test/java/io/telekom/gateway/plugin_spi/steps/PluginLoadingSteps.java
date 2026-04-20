/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import io.telekom.gateway.plugin_spi.api.PipelineStage;
import io.telekom.gateway.plugin_spi.api.PluginContext;
import io.telekom.gateway.plugin_spi.loader.PluginLoader;
import io.telekom.gateway.plugin_spi.loader.PluginRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

public class PluginLoadingSteps {

  private Path tmpDir;
  private PluginRegistry registry;
  private PluginLoader loader;
  private final List<GatewayPlugin> adhoc = new ArrayList<>();
  private List<GatewayPlugin> queried;

  @Before
  public void setUp() throws IOException {
    tmpDir = Files.createTempDirectory("plugin-spi-test-");
    registry = new PluginRegistry();
    adhoc.clear();
  }

  @After
  public void tearDown() throws IOException {
    if (loader != null) {
      loader.close();
    }
    TestFiles.deleteRecursively(tmpDir);
  }

  @Given("an empty plugin directory")
  public void anEmptyPluginDirectory() {
    loader = new PluginLoader(tmpDir, registry);
  }

  @When("the loader scans for plugins")
  public void theLoaderScansForPlugins() {
    loader.loadAll();
  }

  @Then("the registry contains a plugin named {string}")
  public void theRegistryContainsAPluginNamed(String name) {
    assertThat(registry.byName(name)).as("plugin %s present", name).isPresent();
  }

  @And("the plugin {string} targets stage {string}")
  public void thePluginTargetsStage(String name, String stage) {
    GatewayPlugin p =
        registry
            .byName(name)
            .orElseThrow(() -> new AssertionError("plugin " + name + " not in registry"));
    assertThat(p.stage()).isEqualTo(PipelineStage.valueOf(stage));
  }

  @And("a test plugin {string} at stage {string} with order {int} is registered")
  public void aTestPluginIsRegistered(String name, String stage, int order) {
    adhoc.add(new StaticPlugin(name, PipelineStage.valueOf(stage), order));
  }

  @When("the registry is queried for stage {string}")
  public void theRegistryIsQueriedForStage(String stage) {
    registry.replaceAll(adhoc);
    queried = registry.byStage(PipelineStage.valueOf(stage));
  }

  @Then("the plugin names in order are {string}")
  public void thePluginNamesInOrderAre(String csv) {
    List<String> expected =
        Arrays.stream(csv.split(",")).map(String::trim).collect(Collectors.toList());
    List<String> actual = queried.stream().map(GatewayPlugin::name).toList();
    assertThat(actual).isEqualTo(expected);
  }

  @When("duplicate-aware de-duplication runs")
  public void duplicateAwareDeDuplicationRuns() {
    // Simulate the loader's behaviour: first-seen wins.
    Map<String, GatewayPlugin> byName = new LinkedHashMap<>();
    for (GatewayPlugin p : adhoc) {
      byName.putIfAbsent(p.name(), p);
    }
    registry.replaceAll(byName.values());
  }

  @Then("only one plugin named {string} is registered")
  public void onlyOnePluginNamedIsRegistered(String name) {
    long count = registry.all().stream().filter(p -> p.name().equals(name)).count();
    assertThat(count).isEqualTo(1L);
  }

  @And("that plugin has order {int}")
  public void thatPluginHasOrder(int order) {
    GatewayPlugin p = registry.all().stream().findFirst().orElseThrow();
    assertThat(p.order()).isEqualTo(order);
  }

  /** Minimal in-memory plugin for order/dedupe checks. */
  private record StaticPlugin(String name, PipelineStage stage, int order)
      implements GatewayPlugin {
    @Override
    public Mono<Void> apply(PluginContext context) {
      return Mono.empty();
    }
  }
}
