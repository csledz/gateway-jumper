// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Zone-level health payload returned by the admin-status-api. Status values are open-ended, but the
 * CLI special-cases {@code HEALTHY}, {@code DEGRADED} and {@code UNHEALTHY} for coloring.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ZoneHealth(String zone, String status, String message, List<Check> checks) {

  @JsonCreator
  public ZoneHealth(
      @JsonProperty("zone") String zone,
      @JsonProperty("status") String status,
      @JsonProperty("message") String message,
      @JsonProperty("checks") List<Check> checks) {
    this.zone = zone;
    this.status = status == null ? "UNKNOWN" : status;
    this.message = message == null ? "" : message;
    this.checks = checks == null ? List.of() : checks;
  }

  /** Sub-check for rendering e.g. redis, upstream connectivity. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Check(String name, String status, String message) {
    @JsonCreator
    public Check(
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("message") String message) {
      this.name = name == null ? "?" : name;
      this.status = status == null ? "UNKNOWN" : status;
      this.message = message == null ? "" : message;
    }
  }
}
