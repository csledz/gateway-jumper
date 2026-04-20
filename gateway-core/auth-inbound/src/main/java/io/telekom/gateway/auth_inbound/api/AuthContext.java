// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.auth_inbound.api;

/**
 * Immutable result of a successful inbound authentication.
 *
 * <p>Skeleton only. See README for the implementation recipe.
 */
public record AuthContext(
    String principalId,
    String issuer,
    java.util.Map<String, Object> claims,
    java.util.Set<String> scopes,
    Type type,
    String traceId) {

  /** Authenticator kind that produced this context. */
  public enum Type {
    JWT,
    APIKEY,
    BASIC,
    INTROSPECTION
  }

  /** Copy with a different traceId (used when a cache hit reuses an earlier context). */
  public AuthContext withTraceId(String newTraceId) {
    return new AuthContext(principalId, issuer, claims, scopes, type, newTraceId);
  }
}
