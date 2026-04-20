// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.steps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.telekom.gateway.circuit_breaker.api.BhConfig;
import io.telekom.gateway.circuit_breaker.api.CbConfig;
import io.telekom.gateway.circuit_breaker.api.ResiliencePolicy;
import io.telekom.gateway.circuit_breaker.api.RetryConfig;
import io.telekom.gateway.circuit_breaker.registry.ResilienceRegistry;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

public class CircuitBreakerSteps {

  @Autowired private ResilienceRegistry registry;
  @Autowired private WireMockServer wireMockServer;
  @Autowired private World world;

  @LocalServerPort private int port;

  private WebTestClient client() {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Given("a circuit breaker policy with {int} failures in {int} calls opens the circuit")
  public void policy(int failures, int calls) {
    CbConfig cb =
        new CbConfig((float) (failures * 100.0 / calls), calls, calls, Duration.ofMillis(500), 2);
    // No retry, no bulkhead beyond default, so the breaker is easy to reason about.
    ResiliencePolicy policy =
        new ResiliencePolicy(
            cb,
            new BhConfig(64, Duration.ZERO),
            new RetryConfig(0, Duration.ZERO, 1.0, new int[] {}, false));
    registry.register("cb-route", policy);
    world.setLastRouteId("cb-route");
  }

  @And("the upstream returns {int} for {string}")
  public void upstreamReturns(int status, String path) {
    wireMockServer.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
  }

  @When("I send {int} GET requests to {string}")
  public void sendRequests(int n, String path) {
    for (int i = 0; i < n; i++) {
      int status = client().get().uri(path).exchange().returnResult(Void.class).getStatus().value();
      world.getResponseStatuses().add(status);
    }
  }

  @Then("the circuit breaker state is {string}")
  public void cbState(String expected) {
    CircuitBreaker cb = registry.circuitBreaker(world.getLastRouteId());
    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> cb.getState().name().equalsIgnoreCase(expected));
    assertThat(cb.getState().name()).isEqualToIgnoringCase(expected);
  }

  @Then("the last response status is {int}")
  public void lastStatus(int expected) {
    assertThat(world.getResponseStatuses()).isNotEmpty();
    assertThat(world.getResponseStatuses().get(world.getResponseStatuses().size() - 1))
        .isEqualTo(expected);
  }

  @When("I wait {long} ms for the circuit to transition to HALF_OPEN")
  public void waitForHalfOpen(long ms) throws InterruptedException {
    Thread.sleep(ms);
  }
}
