// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.dto;

/** Aggregate cache statistics across known in-process caches. */
public record CacheStatsDto(
    CacheEntryDto tokenCache, CacheEntryDto jwksCache, CacheEntryDto schemaCache) {

  /** Metrics for a single named cache. */
  public record CacheEntryDto(
      String name, long size, long hitCount, long missCount, double hitRatio) {}
}
