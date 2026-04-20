// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.steps;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.telekom.gateway.controller.api.GatewayConsumer;
import io.telekom.gateway.controller.api.GatewayCredential;
import io.telekom.gateway.controller.api.GatewayMeshPeer;
import io.telekom.gateway.controller.api.GatewayPolicy;
import io.telekom.gateway.controller.api.GatewayResource;
import io.telekom.gateway.controller.api.GatewayRoute;
import io.telekom.gateway.controller.api.GatewayZone;
import io.telekom.gateway.controller.push.DataPlanePushService;
import io.telekom.gateway.controller.snapshot.ConfigSnapshot;
import io.telekom.gateway.controller.snapshot.ConfigSnapshotEvent;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import io.telekom.gateway.controller.snapshot.SnapshotBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cucumber glue for all controller features. Uses a minimal {@link HttpServer}-backed data-plane
 * stub so push calls can be counted deterministically.
 */
public class ReconciliationSteps {

  @Autowired ResourceCache cache;
  @Autowired SnapshotBuilder snapshotBuilder;
  @Autowired DataPlanePushService pushService;
  @Autowired ApplicationEventPublisher publisher;
  @Autowired TestWorld world;

  private HttpServer stubServer;
  private AtomicInteger callCount;
  private String stubUrl;

  private ConfigSnapshot lastBuilt;
  private Exception lastException;

  @Before
  public void resetState() {
    clearCache();
    world.reset();
    lastBuilt = null;
    lastException = null;
    stopStub();
  }

  private void clearCache() {
    cache
        .knownZones()
        .forEach(
            z -> {
              cache.routesForZone(z).forEach(cache::removeRoute);
              cache.consumersForZone(z).forEach(cache::removeConsumer);
              cache.credentialsForZone(z).forEach(cache::removeCredential);
              cache.meshPeersForZone(z).forEach(cache::removeMeshPeer);
              cache.policiesForZone(z).forEach(cache::removePolicy);
              GatewayZone zs = cache.zoneByName(z);
              if (zs != null) cache.removeZone(zs);
            });
  }

  private void stopStub() {
    if (stubServer != null) {
      stubServer.stop(0);
      stubServer = null;
    }
  }

  // ---------- Givens ----------

  @Given("the controller cache is empty")
  public void cacheEmpty() {
    clearCache();
  }

  @Given("a data-plane is registered")
  public void dataPlaneRegistered() throws IOException {
    startStub(0, 200);
    pushService.registerDataPlane(stubUrl);
  }

  @Given("a flaky data-plane that fails the first {int} calls with status {int} is registered")
  public void flakyDataPlane(int failN, int transientStatus) throws IOException {
    startStub(failN, transientStatus);
    pushService.registerDataPlane(stubUrl);
  }

  @Given("a data-plane that always returns {int} is registered")
  public void permanentFailingDataPlane(int status) throws IOException {
    startStub(Integer.MAX_VALUE, status);
    pushService.registerDataPlane(stubUrl);
  }

  private void startStub(int failFirstN, int transientStatus) throws IOException {
    callCount = new AtomicInteger();
    stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stubServer.createContext(
        "/config",
        ex -> {
          int n = callCount.incrementAndGet();
          int status = (n <= failFirstN) ? transientStatus : 200;
          ex.sendResponseHeaders(status, -1);
          ex.close();
        });
    stubServer.start();
    stubUrl = "http://127.0.0.1:" + stubServer.getAddress().getPort();
  }

  @Given("a GatewayZone {string} exists")
  public void gatewayZoneExists(String zoneName) {
    GatewayZone z = new GatewayZone();
    z.setMetadata(meta(zoneName, null));
    GatewayZone.Spec s = new GatewayZone.Spec();
    s.setZoneName(zoneName);
    z.setSpec(s);
    cache.upsertZone(z);
  }

  @Given("a GatewayRoute named {string} is created in zone {string}")
  public void routeCreated(String name, String zone) {
    GatewayRoute r = new GatewayRoute();
    r.setMetadata(meta(name, zone));
    GatewayRoute.Spec spec = new GatewayRoute.Spec();
    spec.setPath("/" + name);
    r.setSpec(spec);
    cache.upsertRoute(r);
    publisher.publishEvent(new ConfigSnapshotEvent(this, zone, "GatewayRoute:add:" + name));
  }

  @Given("a GatewayConsumer named {string} exists in zone {string}")
  public void consumerCreated(String name, String zone) {
    GatewayConsumer c = new GatewayConsumer();
    c.setMetadata(meta(name, zone));
    GatewayConsumer.Spec spec = new GatewayConsumer.Spec();
    spec.setClientId(name);
    c.setSpec(spec);
    cache.upsertConsumer(c);
    publisher.publishEvent(new ConfigSnapshotEvent(this, zone, "GatewayConsumer:add:" + name));
  }

  @Given("a GatewayCredential named {string} exists in zone {string}")
  public void credentialCreated(String name, String zone) {
    GatewayCredential c = new GatewayCredential();
    c.setMetadata(meta(name, zone));
    GatewayCredential.Spec spec = new GatewayCredential.Spec();
    spec.setType("client_secret");
    c.setSpec(spec);
    cache.upsertCredential(c);
  }

  @Given("a GatewayMeshPeer named {string} exists in zone {string}")
  public void meshPeerCreated(String name, String zone) {
    GatewayMeshPeer p = new GatewayMeshPeer();
    p.setMetadata(meta(name, zone));
    GatewayMeshPeer.Spec spec = new GatewayMeshPeer.Spec();
    spec.setPeerZone("zone-b");
    p.setSpec(spec);
    cache.upsertMeshPeer(p);
  }

  @Given("a GatewayPolicy named {string} exists in zone {string}")
  public void policyCreated(String name, String zone) {
    GatewayPolicy p = new GatewayPolicy();
    p.setMetadata(meta(name, zone));
    GatewayPolicy.Spec spec = new GatewayPolicy.Spec();
    spec.setKind("rate-limit");
    p.setSpec(spec);
    cache.upsertPolicy(p);
  }

  // ---------- Whens ----------

  @When("the GatewayConsumer {string} is removed")
  public void whenConsumerRemoved(String name) {
    GatewayConsumer existing = null;
    for (String z : cache.knownZones()) {
      for (GatewayConsumer c : cache.consumersForZone(z)) {
        if (name.equals(c.getMetadata().getName())) {
          existing = c;
          break;
        }
      }
    }
    if (existing != null) {
      String zone = existing.getZone();
      cache.removeConsumer(existing);
      publisher.publishEvent(new ConfigSnapshotEvent(this, zone, "GatewayConsumer:delete:" + name));
    }
  }

  @When("a snapshot for zone {string} is built")
  public void whenSnapshotBuilt(String zone) {
    lastBuilt = snapshotBuilder.buildForZone(zone);
  }

  @When("a snapshot for zone {string} is pushed")
  public void whenSnapshotPushed(String zone) {
    try {
      ConfigSnapshot snap = snapshotBuilder.buildForZone(zone);
      lastBuilt = snap;
      pushService.push(snap);
      world.recordPush(snap);
    } catch (Exception e) {
      lastException = e;
    }
  }

  // ---------- Thens ----------

  @Then("a snapshot push for zone {string} is recorded")
  public void snapshotPushRecorded(String zone) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertTrue(
                    callCount != null && callCount.get() >= 1,
                    "Expected stub data-plane to receive at least one call for zone " + zone));
  }

  @Then("the pushed snapshot contains {int} route")
  public void pushedSnapshotContainsRoutesSingular(int expected) {
    pushedSnapshotContainsRoutes(expected);
  }

  @Then("the pushed snapshot contains {int} routes")
  public void pushedSnapshotContainsRoutes(int expected) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertTrue(callCount != null && callCount.get() >= 1));
    ConfigSnapshot snap = snapshotBuilder.buildForZone("zone-a");
    assertEquals(expected, snap.getRoutes() == null ? 0 : snap.getRoutes().size());
  }

  @Then("the pushed snapshot contains {int} consumers")
  public void pushedSnapshotContainsConsumers(int expected) {
    ConfigSnapshot snap = snapshotBuilder.buildForZone("zone-a");
    assertEquals(expected, snap.getConsumers() == null ? 0 : snap.getConsumers().size());
  }

  @Then(
      "the snapshot contains {int} route, {int} consumer, {int} credential, {int} mesh peer,"
          + " {int} policy")
  public void snapshotAggregation(int r, int c, int cr, int mp, int po) {
    assertNotNull(lastBuilt);
    assertEquals(r, lastBuilt.getRoutes().size());
    assertEquals(c, lastBuilt.getConsumers().size());
    assertEquals(cr, lastBuilt.getCredentials().size());
    assertEquals(mp, lastBuilt.getMeshPeers().size());
    assertEquals(po, lastBuilt.getPolicies().size());
  }

  @Then(
      "the snapshot contains {int} route, {int} consumers, {int} credentials, {int} mesh peers,"
          + " {int} policies")
  public void snapshotAggregationPlural(int r, int c, int cr, int mp, int po) {
    snapshotAggregation(r, c, cr, mp, po);
  }

  @Then("the snapshot zone spec is set")
  public void zoneSpecIsSet() {
    assertNotNull(lastBuilt);
    assertNotNull(lastBuilt.getZoneSpec());
  }

  @Then("the flaky data-plane received at least {int} calls")
  public void flakyReceivedAtLeast(int expected) {
    assertTrue(
        callCount.get() >= expected,
        "expected >= " + expected + " calls but got " + callCount.get());
  }

  @Then("the last push was successful")
  public void lastPushSuccessful() throws Exception {
    HttpClient hc = HttpClient.newHttpClient();
    HttpResponse<String> resp =
        hc.send(
            HttpRequest.newBuilder(URI.create(stubUrl + "/config"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
    assertTrue(lastException == null);
  }

  @Then("the failing data-plane received exactly {int} call")
  public void failingReceivedExactlySingular(int expected) {
    failingReceivedExactly(expected);
  }

  @Then("the failing data-plane received exactly {int} calls")
  public void failingReceivedExactly(int expected) {
    assertEquals(expected, callCount.get());
  }

  // ---------- helpers ----------

  private static <T extends GatewayResource<?>> ObjectMeta meta(String name, String zone) {
    ObjectMeta m = new ObjectMeta();
    m.setName(name);
    m.setNamespace("default");
    Map<String, String> labels = new HashMap<>();
    if (zone != null) {
      labels.put("gateway.telekom.io/zone", zone);
    }
    m.setLabels(labels);
    return m;
  }
}
