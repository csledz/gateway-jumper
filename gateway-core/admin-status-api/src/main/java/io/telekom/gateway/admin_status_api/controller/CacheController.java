// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.telekom.gateway.admin_status_api.dto.CacheStatsDto;
import io.telekom.gateway.admin_status_api.service.RuntimeStateReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Read-only view over the gateway's in-process caches (token, JWKS, schema). */
@Slf4j
@RestController
@RequestMapping("/admin/cache-stats")
@RequiredArgsConstructor
@Tag(name = "cache", description = "In-process cache statistics (read-only)")
public class CacheController {

  private final RuntimeStateReader reader;

  @GetMapping
  @Operation(summary = "Snapshot of token cache, JWKS cache and schema cache")
  public Mono<CacheStatsDto> stats() {
    return reader.cacheStats();
  }
}
