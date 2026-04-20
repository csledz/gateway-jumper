// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable evaluation context passed to a {@link PolicyEvaluator}. Exposed as the SpEL root object
 * so policies can reference its fields directly (e.g. {@code #root.scopes.contains('read')}).
 *
 * @param principalId subject id (e.g. {@code sub} claim)
 * @param scopes effective authorization scopes
 * @param claims raw JWT claims (read-only view)
 * @param method HTTP method ({@code GET}, {@code POST}, ...)
 * @param path request path
 * @param headers HTTP headers ({@code Map<String,List<String>>} shape for multi-valued headers)
 */
public record PolicyContext(
    String principalId,
    List<String> scopes,
    Map<String, Object> claims,
    String method,
    String path,
    Map<String, List<String>> headers) {

  public PolicyContext {
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
    claims = claims == null ? Map.of() : Map.copyOf(claims);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  /** First value of a header, case-insensitive, or {@code null}. */
  public String header(String name) {
    if (name == null) {
      return null;
    }
    for (var e : headers.entrySet()) {
      if (name.equalsIgnoreCase(e.getKey())) {
        var list = e.getValue();
        return list == null || list.isEmpty() ? null : list.get(0);
      }
    }
    return null;
  }

  /** Convenience for SpEL: {@code #root.hasScope('admin')}. */
  public boolean hasScope(String scope) {
    return scope != null && scopes.contains(scope);
  }

  /** Convenience for SpEL: {@code #root.claim('tenant')}. */
  public Object claim(String name) {
    return name == null ? null : claims.get(name);
  }
}
