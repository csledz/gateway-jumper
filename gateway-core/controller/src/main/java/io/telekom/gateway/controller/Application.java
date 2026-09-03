// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the gateway-core controller. The controller runs inside the Kubernetes
 * control-plane, watches six CRDs (GatewayRoute, GatewayConsumer, GatewayCredential, GatewayZone,
 * GatewayMeshPeer, GatewayPolicy), aggregates their state into a single immutable snapshot per
 * zone, and pushes that snapshot to the configured data-plane pods.
 *
 * <p><b>HA caveat (CRITIQUE F-008):</b> this entry point does not yet implement leader election.
 * Running more than one controller replica simultaneously will cause both instances to reconcile
 * the CRDs and race each other's snapshot pushes. Until a fabric8 {@code LeaderElector}-based gate
 * lands, the deployment must stay at {@code replicaCount: 1} (enforced in the helm chart default)
 * and HPA must remain disabled. See {@code gateway-core/docs/CRITIQUE.md} F-008.
 */
@Slf4j
@SpringBootApplication
@EnableRetry
@EnableAsync
public class Application {

  public static void main(String[] args) {
    log.warn(
        "gateway-core controller starting WITHOUT leader election (CRITIQUE F-008) — "
            + "run at most one replica until a LeaderElector-based gate is added.");
    SpringApplication.run(Application.class, args);
  }
}
