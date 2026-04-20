// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.rate_limiter.api;

/** Identifies the subject of a rate-limit decision. */
public record RateLimitKey(Scope scope, String id, String route, String api) {

  /** Dimension under which a limit is counted. */
  public enum Scope {
    CONSUMER,
    ROUTE,
    API
  }
}
