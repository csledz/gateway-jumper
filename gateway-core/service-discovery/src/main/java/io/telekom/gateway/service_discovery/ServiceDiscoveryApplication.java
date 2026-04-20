// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone entry point for running service-discovery in isolation. In production it is pulled in
 * as a library dependency and auto-configured into the hosting gateway.
 */
@SpringBootApplication
public class ServiceDiscoveryApplication {

  public static void main(String[] args) {
    SpringApplication.run(ServiceDiscoveryApplication.class, args);
  }
}
