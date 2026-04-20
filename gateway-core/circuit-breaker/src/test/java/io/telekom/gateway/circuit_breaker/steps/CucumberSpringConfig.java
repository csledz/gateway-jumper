// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.steps;

import io.cucumber.spring.CucumberContextConfiguration;
import io.telekom.gateway.circuit_breaker.CircuitBreakerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Boots a full Spring context once per test run for Cucumber glue. */
@CucumberContextConfiguration
@SpringBootTest(
    classes = {CircuitBreakerApplication.class, TestGatewayConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class CucumberSpringConfig {}
