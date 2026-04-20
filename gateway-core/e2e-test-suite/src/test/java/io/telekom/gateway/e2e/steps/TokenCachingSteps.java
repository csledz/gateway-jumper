// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.e2e.MeshTopology;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.mockserver.client.MockServerClient;
import org.testcontainers.containers.MockServerContainer;

/**
 * Fires a burst of requests through the origin zone and asserts that the peer call count stays
 * below a configured ceiling - i.e. that the peer-token cache is actually deduplicating.
 */
@Slf4j
public class TokenCachingSteps {

  private final World world;

  public TokenCachingSteps(World world) {
    this.world = world;
  }

  @When(
      "{int} concurrent GET requests are sent to zone {word} path {string} targeting peer zone"
          + " {word}")
  public void concurrentBurst(int n, String zone, String path, String target) throws Exception {
    // Reset counters on the target upstream so we only measure this burst.
    MeshTopology t = world.topology;
    MockServerContainer targetUpstream = t.upstream(target);
    MockServerClient c =
        new MockServerClient(targetUpstream.getHost(), targetUpstream.getServerPort());
    c.clear(request());
    c.when(request()).respond(org.mockserver.model.HttpResponse.response().withStatusCode(200));

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    URI uri = URI.create(t.proxyUrl(zone) + path);
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("X-Target-Zone", target)
            .GET()
            .build();
    ExecutorService pool = Executors.newFixedThreadPool(32);
    List<CompletableFuture<HttpResponse<String>>> all = new ArrayList<>(n);
    try {
      for (int i = 0; i < n; i++) {
        all.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return client.send(req, HttpResponse.BodyHandlers.ofString());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                },
                pool));
      }
      CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).get();
    } finally {
      pool.shutdownNow();
    }
    world.bag.put("burst.count", n);
    int ok = 0;
    for (CompletableFuture<HttpResponse<String>> f : all) {
      if (f.get().statusCode() == 200) ok++;
    }
    world.bag.put("burst.ok", ok);
    log.info("burst done: {}/{} OK", ok, n);
  }

  @Then("at most {int} peer token(s) were minted from zone {word} to zone {word}")
  public void atMostPeerTokens(int max, String origin, String target) {
    // The embedded proxy's PeerTokenCache tracks every cache MISS with a mintCount counter.
    // When running against a container image, this returns -1 and we fall back to counting
    // distinct Authorization headers seen at the next hop.
    MeshTopology t = world.topology;
    int minted = t.peerTokenMintCount(origin);
    if (minted >= 0) {
      log.info("peer-token mint count (origin={}, target={}): {}", origin, target, minted);
      assertThat(minted).as("peer token mints").isLessThanOrEqualTo(max).isGreaterThan(0);
      return;
    }
    MockServerContainer targetUpstream = t.upstream(target);
    MockServerClient c =
        new MockServerClient(targetUpstream.getHost(), targetUpstream.getServerPort());
    org.mockserver.model.HttpRequest[] recorded = c.retrieveRecordedRequests(request());
    int distinct =
        (int)
            java.util.Arrays.stream(recorded)
                .map(r -> r.getFirstHeader("authorization"))
                .filter(s -> s != null && !s.isEmpty())
                .distinct()
                .count();
    log.info("distinct peer Authorization values observed at upstream-{}: {}", target, distinct);
    assertThat(distinct).as("distinct peer tokens").isLessThanOrEqualTo(max);
  }

  @Then("all {int} responses were HTTP 200")
  public void allResponsesOk(int n) {
    Integer ok = (Integer) world.bag.get("burst.ok");
    assertThat(ok).isEqualTo(n);
  }
}
