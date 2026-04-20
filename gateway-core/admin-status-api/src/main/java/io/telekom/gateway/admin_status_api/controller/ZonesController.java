// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.telekom.gateway.admin_status_api.dto.ZoneDto;
import io.telekom.gateway.admin_status_api.dto.ZoneHealthDto;
import io.telekom.gateway.admin_status_api.service.RuntimeStateReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read-only view over runtime zones. */
@Slf4j
@RestController
@RequestMapping("/admin/zones")
@RequiredArgsConstructor
@Tag(name = "zones", description = "Runtime zones (read-only)")
public class ZonesController {

  private final RuntimeStateReader reader;

  @GetMapping
  @Operation(summary = "List all runtime zones")
  public Flux<ZoneDto> list() {
    return reader.listZones();
  }

  @GetMapping("/{name}/health")
  @Operation(summary = "Aggregate health snapshot for a zone")
  public Mono<ZoneHealthDto> health(@PathVariable String name) {
    return reader
        .zoneHealth(name)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "zone not found: " + name)));
  }
}
