/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.steps;

import static org.awaitility.Awaitility.await;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.plugin_spi.api.PipelineStage;
import io.telekom.gateway.plugin_spi.loader.PluginLoader;
import io.telekom.gateway.plugin_spi.loader.PluginRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class HotReloadSteps {

  private Path tmpDir;
  private Path lastCopied;
  private PluginRegistry registry;
  private PluginLoader loader;

  @Before
  public void setUp() throws IOException {
    tmpDir = Files.createTempDirectory("plugin-spi-hot-");
    registry = new PluginRegistry();
  }

  @After
  public void tearDown() throws IOException {
    if (loader != null) {
      loader.close();
    }
    TestFiles.deleteRecursively(tmpDir);
  }

  @Given("an empty plugin directory with a running watcher")
  public void anEmptyPluginDirectoryWithARunningWatcher() {
    loader = new PluginLoader(tmpDir, registry);
    loader.loadAll();
    loader.startWatching();
  }

  @When("a plugin JAR providing {string} is copied into the directory")
  public void aPluginJarProvidingIsCopiedIntoTheDirectory(String name) throws IOException {
    // Build outside of tmpDir, then atomically move so the watcher sees a single CREATE event
    // AFTER the file is complete.
    Path staging = Files.createTempDirectory("plugin-spi-stage-");
    Path built = TestJarBuilder.build(staging, name, PipelineStage.PRE_UPSTREAM, 999);
    lastCopied = tmpDir.resolve(built.getFileName());
    Files.move(built, lastCopied);
    Files.deleteIfExists(staging);
  }

  @Then("the registry eventually contains a plugin named {string}")
  public void theRegistryEventuallyContainsAPluginNamed(String name) {
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(250))
        .until(() -> registry.byName(name).isPresent());
  }

  @And("that plugin JAR is deleted from the directory")
  public void thatPluginJarIsDeletedFromTheDirectory() throws IOException {
    Files.deleteIfExists(lastCopied);
  }

  @Then("the registry eventually does not contain a plugin named {string}")
  public void theRegistryEventuallyDoesNotContainAPluginNamed(String name) {
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(250))
        .until(() -> registry.byName(name).isEmpty());
  }
}
