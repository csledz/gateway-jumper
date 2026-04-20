/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.auth_outbound.api;

/**
 * Immutable configuration for one outbound auth decision. See {@code README.md} for per-type field
 * usage.
 */
public record OutboundAuthPolicy(
    Type type,
    String clientId,
    String clientSecretRef,
    String tokenEndpoint,
    String internalTokenEndpoint,
    java.util.List<String> scopes,
    String realm,
    String environment,
    String serviceOwner) {

  public enum Type {
    ONE_TOKEN,
    MESH,
    EXTERNAL_OAUTH,
    BASIC,
    TOKEN_EXCHANGE
  }
}
