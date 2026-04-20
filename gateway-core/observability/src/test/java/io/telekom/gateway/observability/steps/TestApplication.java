// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.steps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot test application that exercises the observability module. Beans from the
 * module itself are contributed via {@code ObservabilityAutoConfiguration} — this class only
 * declares a scan root for Cucumber step classes.
 */
@SpringBootApplication
public class TestApplication {
  public static void main(String[] args) {
    SpringApplication.run(TestApplication.class, args);
  }
}
