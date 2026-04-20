// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.filter;

import io.telekom.gateway.mesh_federation.config.MeshFederationProperties;
import io.telekom.gateway.mesh_federation.failover.FailoverSelector;
import io.telekom.gateway.mesh_federation.peer.MeshPeerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@link WebFilter} at order {@link #ORDER} (800) that picks a mesh target zone for the request via
 * {@link FailoverSelector} and records the decision on the exchange attributes. The downstream
 * proxy forwarder (a separate module) is expected to resolve the selected zone into a concrete
 * upstream URL via {@link MeshPeerRegistry}.
 *
 * <p>Candidate zones come from the request attribute keyed by {@link #CANDIDATE_ZONES_ATTR},
 * populated by earlier filters (route policy). When absent, only the local zone is considered.
 *
 * <p>The filter honors the {@code X-Failover-Skip-Zone} request header (comma-separated list) and
 * appends the local zone to the skip list it publishes to the exchange so that downstream hops
 * don't loop back.
 */
@Slf4j
public class MeshFederationFilter implements WebFilter, Ordered {

  public static final int ORDER = 800;
  public static final String CANDIDATE_ZONES_ATTR = "io.telekom.gateway.mesh.candidateZones";
  public static final String SELECTED_ZONE_ATTR = "io.telekom.gateway.mesh.selectedZone";
  public static final String SKIP_ZONE_HEADER = "X-Failover-Skip-Zone";

  private final FailoverSelector selector;
  private final MeshPeerRegistry peers;
  private final MeshFederationProperties props;

  public MeshFederationFilter(
      FailoverSelector selector, MeshPeerRegistry peers, MeshFederationProperties props) {
    this.selector = selector;
    this.peers = peers;
    this.props = props;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    List<String> candidates = candidatesFor(exchange);
    Set<String> skip = skipZones(exchange);

    Optional<String> pick = selector.select(candidates, skip);
    if (pick.isEmpty()) {
      return reject503(exchange, candidates, skip);
    }

    String selected = pick.get();
    exchange.getAttributes().put(SELECTED_ZONE_ATTR, selected);
    if (log.isDebugEnabled()) {
      log.debug("mesh selection: candidates={} skip={} -> {}", candidates, skip, selected);
    }

    ServerWebExchange mutated =
        exchange
            .mutate()
            .request(
                exchange
                    .getRequest()
                    .mutate()
                    .header(SKIP_ZONE_HEADER, appendedSkipHeader(skip))
                    .build())
            .build();
    return chain.filter(mutated);
  }

  private List<String> candidatesFor(ServerWebExchange exchange) {
    Object attr = exchange.getAttributes().get(CANDIDATE_ZONES_ATTR);
    if (attr instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of(props.localZone());
  }

  private Set<String> skipZones(ServerWebExchange exchange) {
    List<String> values =
        exchange.getRequest().getHeaders().getOrDefault(SKIP_ZONE_HEADER, List.of());
    if (values.isEmpty()) {
      return Collections.emptySet();
    }
    return values.stream()
        .flatMap(v -> Arrays.stream(v.split(",")))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  private String appendedSkipHeader(Set<String> existing) {
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(existing);
    out.add(props.localZone());
    return String.join(",", out);
  }

  private Mono<Void> reject503(
      ServerWebExchange exchange, List<String> candidates, Set<String> skip) {
    var response = exchange.getResponse();
    response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    String body =
        "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
            + "\"status\":503,\"detail\":\"No healthy zone in candidate set\","
            + "\"candidateZones\":"
            + jsonArray(candidates)
            + ",\"skippedZones\":"
            + jsonArray(List.copyOf(skip))
            + "}";
    log.warn(
        "mesh rejection: all candidates unhealthy or skipped. candidates={} skip={}",
        candidates,
        skip);
    DataBuffer buf = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    response.getHeaders().setContentLength(buf.readableByteCount());
    response.getHeaders().add(HttpHeaders.CACHE_CONTROL, "no-store");
    // reference peers to keep the compiler honest about the field being used once the
    // downstream proxy integration lands.
    log.trace("known peers at rejection time: {}", peers.all().size());
    return response.writeWith(Mono.just(buf));
  }

  private String jsonArray(List<String> values) {
    return values.stream()
        .map(v -> "\"" + v.replace("\"", "\\\"") + "\"")
        .collect(Collectors.joining(",", "[", "]"));
  }
}
