// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.store;

import java.util.Optional;
import java.util.Set;

/**
 * Opaque credential store used by Basic and API-key authenticators. Implementations must store only
 * hashed verifiers (never plaintext) and compare via a constant-time comparison. A real
 * implementation is populated from the aggregated CRD snapshot by the controller module.
 */
public interface CredentialStore {

  /**
   * Resolve a principal + scopes for an API-key presentation.
   *
   * @param presented the caller's presented key (opaque)
   * @return the resolved principal record, or empty if unknown/revoked
   */
  Optional<PrincipalRecord> resolveApiKey(String presented);

  /**
   * Resolve a principal for a Basic auth presentation.
   *
   * @param identifier left side of the decoded Basic credential (the "username")
   * @param verifier right side of the decoded Basic credential (the "password")
   */
  Optional<PrincipalRecord> resolveBasic(String identifier, String verifier);

  /** A resolved caller identity. */
  record PrincipalRecord(String principalId, Set<String> scopes) {}
}
