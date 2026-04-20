// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointConditionsBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPortBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.k8s.K8sEndpointSliceResolver;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * K8s scenarios drive the resolver directly through its {@link Watcher} surface — this avoids
 * depending on mock-server quirks for watch semantics while still exercising the real model types
 * from fabric8.
 */
public class K8sResolverSteps {

  private final TestWorld world;
  private K8sEndpointSliceResolver resolver;

  public K8sResolverSteps(TestWorld world) {
    this.world = world;
  }

  private void ensureResolver() {
    if (resolver == null) {
      // We drive the resolver through its Watcher<EndpointSlice> surface; start() is intentionally
      // not invoked so no real watch is opened. A Mockito stub is enough to satisfy the
      // non-null constructor contract without pulling okhttp-mockwebserver in.
      resolver = new K8sEndpointSliceResolver(mock(KubernetesClient.class));
    }
  }

  @Given(
      "a k8s namespace {string} containing an EndpointSlice {string} for service {string} with"
          + " addresses {string} on port {int}")
  public void slice(String ns, String name, String svc, String csv, int port) {
    ensureResolver();
    EndpointSlice slice = buildSlice(ns, name, svc, port, Arrays.asList(csv.split(",")), List.of());
    world.slices.put(name, slice);
  }

  @Given(
      "a k8s namespace {string} containing an EndpointSlice {string} for service {string} with one"
          + " ready address {string} and one not-ready address {string} on port {int}")
  public void sliceMixed(
      String ns, String name, String svc, String ready, String notReady, int port) {
    ensureResolver();
    EndpointSlice slice = buildSlice(ns, name, svc, port, List.of(ready), List.of(notReady));
    world.slices.put(name, slice);
  }

  @When("the resolver receives an ADDED event for {string}")
  @And("the resolver has received an ADDED event for {string}")
  public void added(String name) {
    resolver.eventReceived(Watcher.Action.ADDED, world.slices.get(name));
  }

  @When("the EndpointSlice {string} is modified to addresses {string}")
  public void modified(String name, String csv) {
    EndpointSlice prev = world.slices.get(name);
    String ns = prev.getMetadata().getNamespace();
    String svc = prev.getMetadata().getLabels().get(K8sEndpointSliceResolver.SERVICE_NAME_LABEL);
    int port = prev.getPorts().get(0).getPort();
    EndpointSlice updated =
        buildSlice(ns, name, svc, port, Arrays.asList(csv.split(",")), List.of());
    world.slices.put(name, updated);
    resolver.eventReceived(Watcher.Action.MODIFIED, updated);
  }

  @When("the resolver receives a DELETED event for {string}")
  public void deleted(String name) {
    resolver.eventReceived(Watcher.Action.DELETED, world.slices.get(name));
  }

  @Then("resolving {string} returns {int} healthy endpoint\\(s)")
  public void resolves(String uri, int expected) {
    ServiceRef ref = ServiceRef.fromUri(URI.create(uri));
    List<ServiceEndpoint> all = resolver.resolve(ref).block();
    assertThat(all).isNotNull();
    long healthy = all.stream().filter(ServiceEndpoint::healthy).count();
    assertThat(healthy).isEqualTo(expected);
  }

  @Then("resolving {string} returns {int} healthy endpoint\\(s) and {int} unhealthy endpoint\\(s)")
  public void resolvesMixed(String uri, int healthy, int unhealthy) {
    ServiceRef ref = ServiceRef.fromUri(URI.create(uri));
    List<ServiceEndpoint> all = resolver.resolve(ref).block();
    assertThat(all).isNotNull();
    assertThat(all.stream().filter(ServiceEndpoint::healthy).count()).isEqualTo(healthy);
    assertThat(all.stream().filter(e -> !e.healthy()).count()).isEqualTo(unhealthy);
  }

  private EndpointSlice buildSlice(
      String ns, String name, String svc, int port, List<String> ready, List<String> notReady) {
    EndpointSliceBuilder b =
        new EndpointSliceBuilder()
            .withMetadata(
                new ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(ns)
                    .addToLabels(K8sEndpointSliceResolver.SERVICE_NAME_LABEL, svc)
                    .build())
            .withAddressType("IPv4")
            .withPorts(new EndpointPortBuilder().withPort(port).build());
    for (String addr : ready) {
      Endpoint ep =
          new EndpointBuilder()
              .withAddresses(addr.trim())
              .withConditions(new EndpointConditionsBuilder().withReady(true).build())
              .build();
      b.addToEndpoints(ep);
    }
    for (String addr : notReady) {
      Endpoint ep =
          new EndpointBuilder()
              .withAddresses(addr.trim())
              .withConditions(new EndpointConditionsBuilder().withReady(false).build())
              .build();
      b.addToEndpoints(ep);
    }
    return b.build();
  }
}
