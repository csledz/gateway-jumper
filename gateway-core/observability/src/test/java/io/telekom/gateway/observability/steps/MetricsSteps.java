// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.micrometer.core.instrument.MeterRegistry;
import io.telekom.gateway.observability.ObservabilityConstants;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

public class MetricsSteps {

  @Autowired private MeterRegistry meterRegistry;
  @LocalServerPort private int port;

  private double baselineCount = 0;
  private ResponseEntity<String> lastResponse;

  @Given("the current value of gateway.requests for route {string} is captured")
  public void captureBaseline(String ignoredRoute) {
    baselineCount =
        meterRegistry.find(ObservabilityConstants.METRIC_REQUESTS).counters().stream()
            .mapToDouble(c -> c.count())
            .sum();
  }

  @When("a client calls {string} {string}")
  public void callEndpoint(String method, String path) {
    lastResponse =
        WebClient.create("http://localhost:" + port)
            .method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .retrieve()
            .toEntity(String.class)
            .block(Duration.ofSeconds(10));
  }

  @Then("the response status is {int}")
  public void assertStatus(int expected) {
    assertThat(lastResponse).isNotNull();
    assertThat(lastResponse.getStatusCode().value()).isEqualTo(expected);
  }

  @And("the gateway.requests counter has increased")
  public void counterIncreased() {
    double now =
        meterRegistry.find(ObservabilityConstants.METRIC_REQUESTS).counters().stream()
            .mapToDouble(c -> c.count())
            .sum();
    assertThat(now).isGreaterThan(baselineCount);
  }

  @And("the gateway.request.duration timer has at least one sample")
  public void timerHasSamples() {
    long total =
        meterRegistry.find(ObservabilityConstants.METRIC_REQUEST_DURATION).timers().stream()
            .mapToLong(t -> t.count())
            .sum();
    assertThat(total).isGreaterThan(0);
  }

  @And("the metrics carry tags for route, method, status and zone")
  public void checkTags() {
    var counter =
        meterRegistry.find(ObservabilityConstants.METRIC_REQUESTS).counters().stream()
            .findFirst()
            .orElseThrow();
    var keys = counter.getId().getTags().stream().map(t -> t.getKey()).toList();
    assertThat(keys)
        .contains(
            ObservabilityConstants.TAG_ROUTE,
            ObservabilityConstants.TAG_METHOD,
            ObservabilityConstants.TAG_STATUS,
            ObservabilityConstants.TAG_ZONE);
  }

  @And("the response body contains {string}")
  public void bodyContains(String fragment) {
    assertThat(lastResponse.getBody()).contains(fragment);
  }
}
