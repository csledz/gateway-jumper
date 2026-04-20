// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Standalone entry point for the policy-engine module. */
@SpringBootApplication
public class PolicyEngineApplication {

  public static void main(String[] args) {
    SpringApplication.run(PolicyEngineApplication.class, args);
  }
}
