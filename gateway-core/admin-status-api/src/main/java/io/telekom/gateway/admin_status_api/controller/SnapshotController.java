// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.telekom.gateway.admin_status_api.dto.ConsumerDto;
import io.telekom.gateway.admin_status_api.dto.RouteDto;
import io.telekom.gateway.admin_status_api.dto.SnapshotDto;
import io.telekom.gateway.admin_status_api.dto.ZoneDto;
import io.telekom.gateway.admin_status_api.service.RuntimeStateReader;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Bundles routes, zones, consumers and cache stats into a single read-only snapshot. */
@Slf4j
@RestController
@RequestMapping("/admin/snapshot")
@RequiredArgsConstructor
@Tag(name = "snapshot", description = "Aggregated runtime snapshot (read-only)")
public class SnapshotController {

  private final RuntimeStateReader reader;
  private final Clock clock;

  @GetMapping
  @Operation(summary = "Aggregate routes, zones, consumers and cache stats into one document")
  public Mono<SnapshotDto> snapshot() {
    Mono<List<RouteDto>> routes = reader.listRoutes().collectList();
    Mono<List<ZoneDto>> zones = reader.listZones().collectList();
    Mono<List<ConsumerDto>> consumers = reader.listConsumers().collectList();
    return Mono.zip(routes, zones, consumers, reader.cacheStats())
        .map(t -> new SnapshotDto(Instant.now(clock), t.getT1(), t.getT2(), t.getT3(), t.getT4()));
  }
}
