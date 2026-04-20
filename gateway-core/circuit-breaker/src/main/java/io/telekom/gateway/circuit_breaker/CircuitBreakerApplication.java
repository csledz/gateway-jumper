// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Standalone bootstrap for the gateway-core circuit-breaker module. */
@SpringBootApplication
public class CircuitBreakerApplication {

  public static void main(String[] args) {
    SpringApplication.run(CircuitBreakerApplication.class, args);
  }
}
