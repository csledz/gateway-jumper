// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.e2e.MeshTopology;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MockServerContainer;

/** Steps that apply across every feature - topology bootstrap + simple send/assert primitives. */
public class CommonSteps {

  private final World world;
  private final RestClient http = RestClient.builder().build();

  public CommonSteps(World world) {
    this.world = world;
  }

  @Before
  public void scenarioReset() {
    world.reset();
    // Clear any expectations/recorded requests left over from a previous scenario so each
    // feature starts from a clean MockServer state.
    for (String z : MeshTopology.ZONES) {
      MockServerClient c = clientFor(z);
      c.reset();
    }
    // Also clear the cross-zone peer-token cache in each embedded proxy so token-caching
    // features can deterministically measure mint counts.
    world.topology.resetPeerTokenCaches();
    // Ensure every zone starts a scenario marked HEALTHY.
    for (String z : MeshTopology.ZONES) {
      world.topology.getHealthBus().markHealthy(z);
    }
  }

  @Given("the three-zone mesh is up")
  public void theThreeZoneMeshIsUp() {
    MeshTopology t = world.topology; // triggers static init if needed
    assertThat(t.getRedis().isRunning()).isTrue();
    for (String z : MeshTopology.ZONES) {
      assertThat(t.upstream(z).isRunning()).isTrue();
    }
  }

  @Given("upstream in zone {word} replies with {int} and body {string}")
  public void upstreamInZoneReplies(String zone, int status, String body) {
    // Important: MockServerClient.close() stops the *remote* server, so we must NOT use
    // try-with-resources. Letting the local client reference go out of scope is fine.
    MockServerClient c = clientFor(zone);
    c.when(request().withMethod("GET")).respond(response().withStatusCode(status).withBody(body));
  }

  @Given("upstream in zone {word} is primed to echo mesh headers")
  public void upstreamEchoesMeshHeaders(String zone) {
    MockServerClient c = clientFor(zone);
    c.when(request().withMethod("GET")).respond(response().withStatusCode(200).withBody("echoed"));
  }

  private MockServerClient clientFor(String zone) {
    MockServerContainer ms = world.topology.upstream(zone);
    return new MockServerClient(ms.getHost(), ms.getServerPort());
  }

  @When("a GET request is sent to zone {word} path {string}")
  public void sendRequest(String zone, String path) {
    sendWithHeaders(zone, path, HttpHeaders.EMPTY);
  }

  @When("a GET request is sent to zone {word} path {string} targeting peer zone {word}")
  public void sendRequestWithTarget(String zone, String path, String target) {
    HttpHeaders h = new HttpHeaders();
    h.set("X-Target-Zone", target);
    sendWithHeaders(zone, path, h);
  }

  private void sendWithHeaders(String zone, String path, HttpHeaders headers) {
    URI uri = URI.create(world.topology.proxyUrl(zone) + path);
    world.lastResponse =
        http.method(HttpMethod.GET)
            .uri(uri)
            .headers(h -> h.addAll(headers))
            .retrieve()
            .onStatus(
                s -> true,
                (req, res) -> {
                  /* surface any status to the caller */
                })
            .toEntity(String.class);
  }

  @Then("the response status is {int}")
  public void responseStatusIs(int expected) {
    assertThat(world.lastResponse).as("lastResponse").isNotNull();
    assertThat(world.lastResponse.getStatusCode().value()).isEqualTo(expected);
  }

  @Then("the response header {string} equals {string}")
  public void responseHeaderEquals(String name, String expected) {
    HttpHeaders h = world.lastHeaders();
    assertThat(h.getFirst(name)).as(name).isEqualTo(expected);
  }

  @Then("the response header {string} contains {string}")
  public void responseHeaderContains(String name, String expected) {
    HttpHeaders h = world.lastHeaders();
    List<String> vs = h.getOrEmpty(name);
    assertThat(vs).as(name).anyMatch(v -> v.contains(expected));
  }

  @Then("upstream in zone {word} saw {int} request(s)")
  public void upstreamSawRequests(String zone, int count) {
    MockServerClient c = clientFor(zone);
    HttpRequest[] all = c.retrieveRecordedRequests(request());
    assertThat(Arrays.stream(all).count()).isEqualTo(count);
  }
}
