// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.steps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.circuit_breaker.api.BhConfig;
import io.telekom.gateway.circuit_breaker.api.CbConfig;
import io.telekom.gateway.circuit_breaker.api.ResiliencePolicy;
import io.telekom.gateway.circuit_breaker.api.RetryConfig;
import io.telekom.gateway.circuit_breaker.registry.ResilienceRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

public class RetrySteps {

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

  @Given(
      "a retry policy with max {int} attempts, initial backoff {int} ms, multiplier {double}, retry"
          + " on statuses {string}")
  public void retryPolicy(int attempts, int initialMs, double multiplier, String statusesCsv) {
    int[] statuses =
        statusesCsv.isBlank()
            ? new int[0]
            : java.util.Arrays.stream(statusesCsv.split(","))
                .mapToInt(s -> Integer.parseInt(s.trim()))
                .toArray();
    ResiliencePolicy policy =
        new ResiliencePolicy(
            new CbConfig(100f, 1000, 1000, Duration.ofSeconds(30), 3),
            new BhConfig(64, Duration.ZERO),
            new RetryConfig(attempts, Duration.ofMillis(initialMs), multiplier, statuses, false));
    registry.register("retry-route", policy);
    world.setLastRouteId("retry-route");
  }

  @And("the upstream for {string} returns {int} {int} times then {int}")
  public void upstreamFlaky(String path, int errorStatus, int times, int finalStatus) {
    String scenario = "flaky-" + path;
    String currentState = Scenario.STARTED;
    for (int i = 0; i < times; i++) {
      String next = "step-" + (i + 1);
      wireMockServer.stubFor(
          get(urlEqualTo(path))
              .inScenario(scenario)
              .whenScenarioStateIs(currentState)
              .willReturn(aResponse().withStatus(errorStatus))
              .willSetStateTo(next));
      currentState = next;
    }
    wireMockServer.stubFor(
        get(urlEqualTo(path))
            .inScenario(scenario)
            .whenScenarioStateIs(currentState)
            .willReturn(aResponse().withStatus(finalStatus)));
  }

  @And("the upstream POST for {string} returns {int}")
  public void upstreamPostFails(String path, int status) {
    wireMockServer.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
  }

  @When("I send a GET request to {string}")
  public void sendGet(String path) {
    long start = System.nanoTime();
    int status = client().get().uri(path).exchange().returnResult(Void.class).getStatus().value();
    world.getResponseStatuses().add(status);
    world.getResponseDurationsMillis().add((System.nanoTime() - start) / 1_000_000);
  }

  @When("I send a POST request to {string}")
  public void sendPost(String path) {
    int status = client().post().uri(path).exchange().returnResult(Void.class).getStatus().value();
    world.getResponseStatuses().add(status);
  }

  @Then("the upstream received {int} GET requests for {string}")
  public void countGet(int n, String path) {
    assertThat(wireMockServer.findAll(getRequestedFor(urlEqualTo(path))).size()).isEqualTo(n);
  }

  @Then("the upstream received {int} POST requests for {string}")
  public void countPost(int n, String path) {
    assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(path))).size()).isEqualTo(n);
  }

  @Then("the response status is {int}")
  public void status(int expected) {
    assertThat(world.getResponseStatuses().get(world.getResponseStatuses().size() - 1))
        .isEqualTo(expected);
  }

  @Then("the request took at least {int} ms")
  public void timing(int ms) {
    assertThat(
            world.getResponseDurationsMillis().get(world.getResponseDurationsMillis().size() - 1))
        .isGreaterThanOrEqualTo((long) ms);
  }
}
