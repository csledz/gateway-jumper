// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.service;

import io.telekom.gateway.admin_status_api.dto.CacheStatsDto;
import io.telekom.gateway.admin_status_api.dto.ConsumerDto;
import io.telekom.gateway.admin_status_api.dto.RouteDto;
import io.telekom.gateway.admin_status_api.dto.ZoneDto;
import io.telekom.gateway.admin_status_api.dto.ZoneHealthDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only view over the gateway's runtime state.
 *
 * <p>Sibling modules contribute the authoritative implementation. The default in-memory
 * implementation shipped with this module is used for self-tests so the admin API is exercisable in
 * isolation.
 */
public interface RuntimeStateReader {

  Flux<RouteDto> listRoutes();

  Mono<RouteDto> findRoute(String id);

  Flux<ZoneDto> listZones();

  Mono<ZoneHealthDto> zoneHealth(String name);

  Flux<ConsumerDto> listConsumers();

  Mono<ConsumerDto> findConsumer(String id);

  Mono<CacheStatsDto> cacheStats();
}
