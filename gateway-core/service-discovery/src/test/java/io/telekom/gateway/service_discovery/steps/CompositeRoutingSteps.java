// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import io.telekom.gateway.service_discovery.composite.CompositeResolver;
import io.telekom.gateway.service_discovery.lb.WeightedRoundRobin;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

public class CompositeRoutingSteps {

  private final TestWorld world;
  private CompositeResolver composite;
  private List<ServiceEndpoint> weightedEndpoints;

  public CompositeRoutingSteps(TestWorld world) {
    this.world = world;
  }

  @Given("a composite resolver wired with stub k8s and stub dns resolvers")
  public void wire() {
    ServiceResolver k8s = stub("k8s", world.k8sCalls);
    ServiceResolver dns = stub("dns", world.dnsCalls);
    composite = new CompositeResolver(List.of(k8s, dns));
  }

  private static ServiceResolver stub(String scheme, AtomicInteger counter) {
    return new ServiceResolver() {
      @Override
      public String scheme() {
        return scheme;
      }

      @Override
      public Mono<List<ServiceEndpoint>> resolve(ServiceRef ref) {
        counter.incrementAndGet();
        return Mono.just(List.of(ServiceEndpoint.of("127.0.0.1", 80, "http")));
      }
    };
  }

  @When("the composite resolves {string}")
  public void resolve(String uri) {
    try {
      composite.resolve(ServiceRef.fromUri(URI.create(uri))).block();
    } catch (Throwable t) {
      world.lastError = t;
    }
  }

  @Then("the {string} resolver is invoked")
  public void invoked(String scheme) {
    AtomicInteger c = "k8s".equals(scheme) ? world.k8sCalls : world.dnsCalls;
    assertThat(c.get()).isGreaterThan(0);
  }

  @And("the {string} resolver is not invoked")
  public void notInvoked(String scheme) {
    AtomicInteger c = "k8s".equals(scheme) ? world.k8sCalls : world.dnsCalls;
    assertThat(c.get()).isZero();
  }

  @Then("the composite resolution errors with {string}")
  public void erred(String msg) {
    assertThat(world.lastError).isNotNull();
    assertThat(world.lastError.getMessage()).isEqualTo(msg);
  }

  // ---- Weighted round-robin ---------------------------------------------------------------

  @Given("endpoints {string}")
  public void endpoints(String spec) {
    // Format: "host:port@weight=N,host:port@weight=N"
    List<ServiceEndpoint> list = new ArrayList<>();
    for (String part : spec.split(",")) {
      String[] hpw = part.split("@weight=");
      String[] hp = hpw[0].split(":");
      int weight = Integer.parseInt(hpw[1]);
      list.add(new ServiceEndpoint(hp[0], Integer.parseInt(hp[1]), "http", true, weight));
    }
    this.weightedEndpoints = list;
  }

  @When("picking {int} times with weighted round-robin")
  public void pick(int n) {
    WeightedRoundRobin lb = new WeightedRoundRobin();
    for (int i = 0; i < n; i++) {
      ServiceEndpoint e = lb.pick(weightedEndpoints);
      String key = e.host() + ":" + e.port();
      world.pickCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }
  }

  @Then("{string} is chosen between {int} and {int} times")
  public void chosen(String hostPort, int lo, int hi) {
    int actual = world.pickCounts.getOrDefault(hostPort, new AtomicInteger()).get();
    assertThat(actual).isBetween(lo, hi);
  }
}
