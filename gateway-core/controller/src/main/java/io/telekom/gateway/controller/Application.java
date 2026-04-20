// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the gateway-core controller. The controller runs inside the Kubernetes
 * control-plane, watches six CRDs (GatewayRoute, GatewayConsumer, GatewayCredential, GatewayZone,
 * GatewayMeshPeer, GatewayPolicy), aggregates their state into a single immutable snapshot per
 * zone, and pushes that snapshot to the configured data-plane pods.
 */
@SpringBootApplication
@EnableRetry
@EnableAsync
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
