// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.steps;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cucumber-Spring bootstrap. Declares a tiny Spring context that only exposes the shared {@link
 * World}. The real topology lives as a static singleton inside {@link
 * io.telekom.gateway.e2e.MeshTopology} - no full Spring container needed for it.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = TestContext.Config.class)
public class TestContext {

  @Configuration
  public static class Config {
    @Bean
    World world() {
      return new World();
    }
  }
}
