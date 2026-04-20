// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.telekom.gateway.admin_status_api.dto.RouteDto;
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

/** Read-only view over runtime routes. */
@Slf4j
@RestController
@RequestMapping("/admin/routes")
@RequiredArgsConstructor
@Tag(name = "routes", description = "Runtime routes (read-only)")
public class RoutesController {

  private final RuntimeStateReader reader;

  @GetMapping
  @Operation(summary = "List all runtime routes")
  public Flux<RouteDto> list() {
    return reader.listRoutes();
  }

  @GetMapping("/{id}")
  @Operation(summary = "Fetch a single runtime route by id")
  public Mono<RouteDto> get(@PathVariable String id) {
    return reader
        .findRoute(id)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "route not found: " + id)));
  }
}
