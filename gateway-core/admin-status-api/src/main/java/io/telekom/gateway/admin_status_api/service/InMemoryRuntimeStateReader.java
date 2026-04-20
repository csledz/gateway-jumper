// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.service;

import io.telekom.gateway.admin_status_api.dto.CacheStatsDto;
import io.telekom.gateway.admin_status_api.dto.CacheStatsDto.CacheEntryDto;
import io.telekom.gateway.admin_status_api.dto.ConsumerDto;
import io.telekom.gateway.admin_status_api.dto.RouteDto;
import io.telekom.gateway.admin_status_api.dto.ZoneDto;
import io.telekom.gateway.admin_status_api.dto.ZoneHealthDto;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default {@link RuntimeStateReader} implementation backed by in-memory stores.
 *
 * <p>This is intended as a seam for integration tests and for running the module in isolation. Real
 * deployments wire a reader that observes the authoritative CRDs.
 */
@Slf4j
public class InMemoryRuntimeStateReader implements RuntimeStateReader {

  private final ConcurrentMap<String, RouteDto> routes = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ZoneDto> zones = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, ConsumerDto> consumers = new ConcurrentHashMap<>();
  private final Clock clock;
  private volatile CacheStatsDto cacheStats;

  public InMemoryRuntimeStateReader(Clock clock) {
    this.clock = clock;
    this.cacheStats =
        new CacheStatsDto(
            new CacheEntryDto("token-cache", 0, 0, 0, 0.0),
            new CacheEntryDto("jwks-cache", 0, 0, 0, 0.0),
            new CacheEntryDto("schema-cache", 0, 0, 0, 0.0));
  }

  /** Replaces the current routes with the given collection. Used by tests and the seed loader. */
  public void putRoute(RouteDto route) {
    routes.put(route.id(), route);
  }

  public void putZone(ZoneDto zone) {
    zones.put(zone.name(), zone);
  }

  public void putConsumer(ConsumerDto consumer) {
    consumers.put(consumer.id(), consumer);
  }

  public void setCacheStats(CacheStatsDto stats) {
    this.cacheStats = stats;
  }

  @Override
  public Flux<RouteDto> listRoutes() {
    return Flux.fromIterable(routes.values());
  }

  @Override
  public Mono<RouteDto> findRoute(String id) {
    RouteDto hit = routes.get(id);
    return hit == null ? Mono.empty() : Mono.just(hit);
  }

  @Override
  public Flux<ZoneDto> listZones() {
    return Flux.fromIterable(zones.values());
  }

  @Override
  public Mono<ZoneHealthDto> zoneHealth(String name) {
    ZoneDto zone = zones.get(name);
    if (zone == null) {
      return Mono.empty();
    }
    long healthy = routes.values().stream().filter(r -> name.equals(r.zone())).count();
    String status = healthy == zone.routeCount() && healthy > 0 ? "HEALTHY" : "DEGRADED";
    return Mono.just(
        new ZoneHealthDto(name, status, (int) healthy, zone.routeCount(), Instant.now(clock)));
  }

  @Override
  public Flux<ConsumerDto> listConsumers() {
    return Flux.fromIterable(consumers.values());
  }

  @Override
  public Mono<ConsumerDto> findConsumer(String id) {
    ConsumerDto hit = consumers.get(id);
    return hit == null ? Mono.empty() : Mono.just(hit);
  }

  @Override
  public Mono<CacheStatsDto> cacheStats() {
    return Mono.just(cacheStats);
  }

  /** Pre-seeds a minimal, predictable state so the module is useful out of the box for demos. */
  public void seedDefaults() {
    log.debug("Seeding admin-status-api with default in-memory demo state");
    Instant now = Instant.now(clock);
    putZone(new ZoneDto("zone-a", "dev", 1));
    putZone(new ZoneDto("zone-b", "prod", 0));
    putRoute(
        new RouteDto(
            "echo-route",
            "zone-a",
            "http://echo.local",
            List.of("Path=/echo/**"),
            List.of("StripPrefix=1"),
            Map.of("team", "gateway"),
            now));
    putConsumer(new ConsumerDto("demo-client", "Demo Client", List.of("read"), "zone-a"));
  }
}
