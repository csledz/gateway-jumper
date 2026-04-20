// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the gateway-core proxy skeleton module. */
@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
