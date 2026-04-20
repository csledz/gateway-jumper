// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.steps;

import io.cucumber.spring.CucumberContextConfiguration;
import io.telekom.gateway.policy_engine.filter.PolicyFilter;
import io.telekom.gateway.policy_engine.registry.PolicyRegistry;
import io.telekom.gateway.policy_engine.rego.RegoPolicyEvaluator;
import io.telekom.gateway.policy_engine.spel.SpelPolicyEvaluator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots a slim Spring context for the Cucumber scenarios — just the policy-engine beans, no Spring
 * Cloud Gateway auto-configuration (which requires servlet/netty infrastructure we don't need
 * here).
 */
@CucumberContextConfiguration
@SpringBootTest(
    classes = CucumberSpringConfig.TestConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {"gateway.policy.rego.enabled=true"})
public class CucumberSpringConfig {

  @org.springframework.boot.SpringBootConfiguration
  @ComponentScan(
      basePackageClasses = {
        PolicyRegistry.class,
        SpelPolicyEvaluator.class,
        RegoPolicyEvaluator.class,
        PolicyFilter.class
      })
  public static class TestConfig {}
}
