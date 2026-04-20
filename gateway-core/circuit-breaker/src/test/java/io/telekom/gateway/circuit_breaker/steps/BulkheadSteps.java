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
import io.telekom.gateway.circuit_breaker.api.BhConfig;
import io.telekom.gateway.circuit_breaker.api.CbConfig;
import io.telekom.gateway.circuit_breaker.api.ResiliencePolicy;
import io.telekom.gateway.circuit_breaker.api.RetryConfig;
import io.telekom.gateway.circuit_breaker.registry.ResilienceRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

public class BulkheadSteps {

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

  @Given("a bulkhead policy with max concurrent {int}")
  public void bhPolicy(int maxConcurrent) {
    ResiliencePolicy policy =
        new ResiliencePolicy(
            new CbConfig(100f, 1000, 1000, Duration.ofSeconds(30), 3),
            new BhConfig(maxConcurrent, Duration.ZERO),
            new RetryConfig(0, Duration.ZERO, 1.0, new int[] {}, false));
    registry.register("bh-route", policy);
    world.setLastRouteId("bh-route");
  }

  @And("the upstream for {string} delays responses by {int} ms with status {int}")
  public void slowUpstream(String path, int delayMs, int status) {
    wireMockServer.stubFor(
        get(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withFixedDelay(delayMs)));
  }

  @When("I send {int} concurrent GET requests to {string}")
  public void concurrent(int n, String path) throws ExecutionException, InterruptedException {
    var exec = Executors.newFixedThreadPool(Math.max(2, n));
    try {
      List<Integer> statuses =
          exec
              .invokeAll(
                  IntStream.range(0, n)
                      .<java.util.concurrent.Callable<Integer>>mapToObj(
                          i ->
                              () ->
                                  client()
                                      .get()
                                      .uri(path)
                                      .exchange()
                                      .returnResult(Void.class)
                                      .getStatus()
                                      .value())
                      .toList())
              .stream()
              .map(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .collect(Collectors.toList());
      world.getResponseStatuses().addAll(statuses);
    } finally {
      exec.shutdownNow();
    }
  }

  @Then("at least {int} responses have status {int}")
  public void atLeast(int expected, int status) {
    long count = world.getResponseStatuses().stream().filter(s -> s == status).count();
    assertThat(count).as("responses with %s", status).isGreaterThanOrEqualTo(expected);
  }

  @Then("at most {int} responses have status {int}")
  public void atMost(int expected, int status) {
    long count = world.getResponseStatuses().stream().filter(s -> s == status).count();
    assertThat(count).as("responses with %s", status).isLessThanOrEqualTo(expected);
  }
}
