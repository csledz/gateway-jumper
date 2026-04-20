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
import io.telekom.gateway.service_discovery.dns.StaticDnsResolver;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;

public class DnsResolverSteps {

  private final TestWorld world;
  private StaticDnsResolver resolver;
  private Duration ttl = Duration.ofSeconds(30);

  public DnsResolverSteps(TestWorld world) {
    this.world = world;
  }

  private StaticDnsResolver resolver() {
    if (resolver == null) {
      resolver = new StaticDnsResolver(ttl, world::lookup);
    }
    return resolver;
  }

  @Given("a DNS entry {string} with addresses {string}")
  public void entry(String host, String csv) {
    world.dnsZone.put(host, Arrays.asList(csv.split(",")));
  }

  @Given("the DNS TTL is {int} seconds")
  public void ttl(int seconds) {
    this.ttl = Duration.ofSeconds(seconds);
    this.resolver = null; // force rebuild with new TTL
  }

  @Given("a DNS lookup that throws UnknownHostException for {string}")
  public void throwing(String host) {
    world.dnsFailure = new UnknownHostException(host);
  }

  @When("the DNS resolver resolves {string}")
  public void resolve(String uri) {
    ServiceRef ref = ServiceRef.fromUri(URI.create(uri));
    world.lastEndpoints = resolver().resolve(ref).block();
  }

  @And("the DNS entry {string} changes to addresses {string}")
  public void change(String host, String csv) {
    world.dnsZone.put(host, Arrays.asList(csv.split(",")));
  }

  @And("the DNS resolver resolves {string} again")
  public void resolveAgain(String uri) {
    resolve(uri);
  }

  @Then("the resolver returns {int} endpoint\\(s)")
  public void count(int expected) {
    assertThat(world.lastEndpoints).isNotNull();
    assertThat(world.lastEndpoints).hasSize(expected);
  }

  @Then("the resolver returns endpoint {string}")
  public void single(String hostPort) {
    assertThat(world.lastEndpoints).isNotNull().hasSize(1);
    ServiceEndpoint e = world.lastEndpoints.get(0);
    assertThat(e.host() + ":" + e.port()).isEqualTo(hostPort);
  }
}
