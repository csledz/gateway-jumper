/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.steps;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Minimal Spring context for Cucumber. The steps themselves don't need WebFlux runtime wiring —
 * they exercise {@link io.telekom.gateway.plugin_spi.loader.PluginLoader} and {@link
 * io.telekom.gateway.plugin_spi.loader.PluginRegistry} directly.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = CucumberSpringConfig.TestApp.class)
public class CucumberSpringConfig {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}
}
