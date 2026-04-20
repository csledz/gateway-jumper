// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.telekom.gateway.mesh_federation.config.MeshFederationProperties;
import io.telekom.gateway.mesh_federation.failover.FailoverSelector;
import io.telekom.gateway.mesh_federation.health.ZoneHealthRegistry;
import io.telekom.gateway.mesh_federation.model.ZoneHealth;
import io.telekom.gateway.mesh_federation.peer.MeshPeerRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class MeshFederationFilterTest {

  private final ZoneHealthRegistry registry =
      new ZoneHealthRegistry(new SimpleMeterRegistry(), Clock.systemUTC());
  private final FailoverSelector selector = new FailoverSelector(registry);
  private final MeshPeerRegistry peers = new MeshPeerRegistry();
  private final MeshFederationProperties props =
      new MeshFederationProperties("zone-local", "realm-a", 5, 15, "gateway-zone-status");
  private final MeshFederationFilter filter = new MeshFederationFilter(selector, peers, props);

  @Test
  void picksLocalZoneWhenHealthyAndNoCandidatesAttr() {
    registry.accept(new ZoneHealth("zone-local", true, 0, "zone-local"));
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    WebFilterChain chain =
        ex -> {
          assertThat(ex.getAttributes().get(MeshFederationFilter.SELECTED_ZONE_ATTR))
              .isEqualTo("zone-local");
          return Mono.empty();
        };

    filter.filter(exchange, chain).block();
  }

  @Test
  void respectsSkipHeaderAndPicksNextHealthyCandidate() {
    registry.accept(new ZoneHealth("zone-a", true, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-b", true, 0, "reporter"));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/").header(MeshFederationFilter.SKIP_ZONE_HEADER, "zone-a"));
    exchange
        .getAttributes()
        .put(MeshFederationFilter.CANDIDATE_ZONES_ATTR, List.of("zone-a", "zone-b"));

    WebFilterChain chain =
        ex -> {
          assertThat(ex.getAttributes().get(MeshFederationFilter.SELECTED_ZONE_ATTR))
              .isEqualTo("zone-b");
          List<String> skip =
              ex.getRequest().getHeaders().get(MeshFederationFilter.SKIP_ZONE_HEADER);
          assertThat(skip).isNotNull();
          assertThat(String.join(",", skip)).contains("zone-a").contains("zone-local");
          return Mono.empty();
        };

    filter.filter(exchange, chain).block();
  }

  @Test
  void rejects503WhenAllUnhealthy() {
    registry.accept(new ZoneHealth("zone-a", false, 0, "reporter"));
    registry.accept(new ZoneHealth("zone-b", false, 0, "reporter"));
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    exchange
        .getAttributes()
        .put(MeshFederationFilter.CANDIDATE_ZONES_ATTR, List.of("zone-a", "zone-b"));

    filter
        .filter(
            exchange,
            ex -> {
              throw new AssertionError("chain should not run when all candidates are unhealthy");
            })
        .block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
