// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.api;

import java.util.Optional;

/**
 * Resolves the (username, verifier) pair for a Basic-auth outbound policy. Real implementations
 * read the materials from the CRD-sourced secret store; the demo implementation in {@code test/}
 * keeps them in an in-memory map.
 */
@FunctionalInterface
public interface BasicCredentialResolver {
  Optional<UsernamePassword> resolve(OutboundAuthPolicy policy);

  /** Plain-pair carrier; lives only long enough to form a Basic header. */
  record UsernamePassword(String username, String password) {}
}
