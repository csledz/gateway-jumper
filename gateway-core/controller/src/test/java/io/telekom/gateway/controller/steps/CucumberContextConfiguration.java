// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.steps;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.telekom.gateway.controller.Application;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot / Cucumber bootstrap. Replaces the real kubernetes client with a {@link
 * KubernetesMockServer}-backed client so Cucumber steps can drive CRD CRUD against an in-memory API
 * server.
 *
 * <p>Pattern borrowed from {@code jumper/config/CucumberContextConfiguration.java}.
 */
@io.cucumber.spring.CucumberContextConfiguration
@ActiveProfiles("test")
@SpringBootTest(
    classes = {Application.class, CucumberContextConfiguration.MockK8sConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberContextConfiguration {

  @TestConfiguration
  static class MockK8sConfig {

    @Bean(destroyMethod = "destroy")
    public KubernetesMockServer kubernetesMockServer() {
      KubernetesMockServer server = new KubernetesMockServer(false);
      server.init();
      return server;
    }

    @Bean
    @Primary
    public KubernetesClient kubernetesClient(KubernetesMockServer server) {
      return server.createClient();
    }
  }
}
