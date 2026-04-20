// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.annotation.PreDestroy;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires a WireMock backend and a single gateway route that hits it. */
@Configuration
public class TestGatewayConfig {

  private final WireMockServer wireMockServer =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @Bean
  public WireMockServer wireMockServer() {
    if (!wireMockServer.isRunning()) {
      wireMockServer.start();
    }
    return wireMockServer;
  }

  @Bean
  public RouteLocator routes(RouteLocatorBuilder builder, WireMockServer mock) {
    return builder
        .routes()
        .route("cb-route", r -> r.path("/cb/**").filters(f -> f.stripPrefix(1)).uri(mock.baseUrl()))
        .route("bh-route", r -> r.path("/bh/**").filters(f -> f.stripPrefix(1)).uri(mock.baseUrl()))
        .route(
            "retry-route",
            r -> r.path("/retry/**").filters(f -> f.stripPrefix(1)).uri(mock.baseUrl()))
        .build();
  }

  @PreDestroy
  public void stop() {
    if (wireMockServer.isRunning()) {
      wireMockServer.stop();
    }
  }
}
