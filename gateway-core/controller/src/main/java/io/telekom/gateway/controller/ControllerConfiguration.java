// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ControllerConfiguration {

  /**
   * In-cluster kubernetes client. Fabric8 auto-resolves service-account token / api-server URL when
   * running inside a pod; falls back to {@code ~/.kube/config} otherwise.
   */
  @Bean(destroyMethod = "close")
  public KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
