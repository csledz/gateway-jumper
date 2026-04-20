// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.admin_status_api.dto.RouteDto;
import io.telekom.gateway.admin_status_api.dto.ZoneDto;
import io.telekom.gateway.admin_status_api.service.InMemoryRuntimeStateReader;
import io.telekom.gateway.admin_status_api.service.RuntimeStateReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

public class AdminApiSteps {

  @Autowired private RuntimeStateReader reader;
  @LocalServerPort private int port;

  @Value("${admin.security.user}")
  private String adminUser;

  @Value("${admin.security.password}")
  private String adminPassword;

  private HttpStatusCode lastStatus;
  private String lastBody;

  private WebClient client(boolean authenticated) {
    WebClient.Builder builder =
        WebClient.builder()
            .baseUrl("http://localhost:" + port)
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create()));
    if (authenticated) {
      builder.defaultHeaders(h -> h.setBasicAuth(adminUser, adminPassword));
    }
    return builder.build();
  }

  @Given("the admin API is seeded with a route {string} in zone {string}")
  public void seedRoute(String routeId, String zoneName) {
    InMemoryRuntimeStateReader mem = (InMemoryRuntimeStateReader) reader;
    mem.putZone(new ZoneDto(zoneName, "test", 1));
    mem.putRoute(
        new RouteDto(
            routeId,
            zoneName,
            "http://upstream.local",
            List.of("Path=/**"),
            List.of(),
            Map.of(),
            Instant.parse("2026-01-01T00:00:00Z")));
  }

  @Given("the admin API is seeded with a zone {string} with {int} route")
  public void seedZone(String zoneName, int routeCount) {
    InMemoryRuntimeStateReader mem = (InMemoryRuntimeStateReader) reader;
    mem.putZone(new ZoneDto(zoneName, "test", routeCount));
    if (routeCount > 0) {
      mem.putRoute(
          new RouteDto(
              zoneName + "-route-1",
              zoneName,
              "http://upstream.local",
              List.of("Path=/**"),
              List.of(),
              Map.of(),
              Instant.parse("2026-01-01T00:00:00Z")));
    }
  }

  @When("an authenticated client calls GET {string}")
  public void authenticatedGet(String path) {
    call(true, path);
  }

  @When("an unauthenticated client calls GET {string}")
  public void unauthenticatedGet(String path) {
    call(false, path);
  }

  private void call(boolean authenticated, String path) {
    try {
      var response =
          client(authenticated)
              .get()
              .uri(path)
              .retrieve()
              .toEntity(String.class)
              .block(Duration.ofSeconds(10));
      lastStatus = response.getStatusCode();
      lastBody = response.getBody() == null ? "" : response.getBody();
    } catch (WebClientResponseException e) {
      lastStatus = e.getStatusCode();
      lastBody = e.getResponseBodyAsString();
    }
  }

  @Then("the response status is {int}")
  public void assertStatus(int expected) {
    assertThat(lastStatus).isNotNull();
    assertThat(lastStatus.value()).isEqualTo(expected);
  }

  @And("the response body contains {string}")
  public void bodyContains(String fragment) {
    assertThat(lastBody).contains(fragment);
  }
}
