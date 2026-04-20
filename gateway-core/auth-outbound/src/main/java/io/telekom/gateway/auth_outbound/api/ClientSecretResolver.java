// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.api;

import java.util.Optional;

/**
 * Resolves an opaque client-secret reference into the actual secret string. Real implementations
 * read from a {@code Secret} mounted at runtime; the test fixture uses an in-memory map.
 */
@FunctionalInterface
public interface ClientSecretResolver {
  Optional<String> resolve(String secretRef);
}
