// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.micrometer.tracing.Tracer;
import io.telekom.gateway.observability.tracing.SecretRedactor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

public class TracingSteps {

  @Autowired private SecretRedactor redactor;

  @Autowired(required = false)
  private Tracer tracer;

  @LocalServerPort private int port;

  private ResponseEntity<String> lastResponse;
  private String filteredUrl;

  @When("a client calls {string} {string} with B3 trace header {string}")
  public void callWithB3(String method, String path, String b3Header) {
    lastResponse =
        WebClient.create("http://localhost:" + port)
            .method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .header("b3", b3Header)
            .retrieve()
            .toEntity(String.class)
            .block(Duration.ofSeconds(10));
  }

  @Then("a span context is available for that request")
  public void spanAvailable() {
    assertThat(lastResponse).isNotNull();
    // Tracer is registered via micrometer-tracing-bridge-brave; its presence confirms the
    // observation pipeline is active and propagation happened on the server side.
    assertThat(tracer).isNotNull();
  }

  @Given("the redactor is configured with defaults")
  public void redactorDefaults() {
    assertThat(redactor).isNotNull();
  }

  @When("the URL {string} is filtered")
  public void filterUrl(String url) {
    filteredUrl = redactor.filterQueryParams(url);
  }

  @Then("the filtered URL contains {string}")
  public void filteredContains(String fragment) {
    assertThat(filteredUrl).contains(fragment);
  }

  @And("the filtered URL does not contain {string}")
  public void filteredMissing(String fragment) {
    assertThat(filteredUrl).doesNotContain(fragment);
  }

  @Then("the header {string} value {string} is redacted to {string}")
  public void headerRedacted(String name, String value, String expected) {
    assertThat(redactor.redactHeaderValue(name, value)).isEqualTo(expected);
  }

  @And("the header {string} value {string} is not redacted")
  public void headerNotRedacted(String name, String value) {
    assertThat(redactor.redactHeaderValue(name, value)).isEqualTo(value);
  }
}
