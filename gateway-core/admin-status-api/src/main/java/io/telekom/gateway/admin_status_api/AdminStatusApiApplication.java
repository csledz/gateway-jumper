// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone bootstrap for the gateway-core admin-status-api module.
 *
 * <p>Exposes a READ-ONLY HTTP surface over the live runtime state of the gateway (routes, zones,
 * consumers, caches). Mutations are performed exclusively via kubectl against the CRDs.
 */
@SpringBootApplication
public class AdminStatusApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(AdminStatusApiApplication.class, args);
  }
}
