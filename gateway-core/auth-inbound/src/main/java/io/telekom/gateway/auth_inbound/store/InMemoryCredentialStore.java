// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CredentialStore} used by standalone tests and as a bootstrap. Stores SHA-256
 * digests of the presented credentials and compares via {@link MessageDigest#isEqual(byte[],
 * byte[])} (constant-time).
 */
public class InMemoryCredentialStore implements CredentialStore {

  private final Map<String, PrincipalRecord> apiKeyByDigest = new ConcurrentHashMap<>();
  private final Map<String, PrincipalRecord> basicByDigest = new ConcurrentHashMap<>();

  /** Register an API-key entry. The digest is computed once and stored; {@code rawKey} is not. */
  public InMemoryCredentialStore registerApiKey(
      String rawKey, String principalId, Set<String> scopes) {
    apiKeyByDigest.put(digest(rawKey), new PrincipalRecord(principalId, Set.copyOf(scopes)));
    return this;
  }

  /**
   * Register a Basic-auth entry keyed by {@code identifier:verifier} so that lookups can keep the
   * constant-time compare on the verifier portion.
   */
  public InMemoryCredentialStore registerBasic(
      String identifier, String verifier, String principalId, Set<String> scopes) {
    basicByDigest.put(
        digest(identifier + ":" + verifier), new PrincipalRecord(principalId, Set.copyOf(scopes)));
    return this;
  }

  @Override
  public Optional<PrincipalRecord> resolveApiKey(String presented) {
    if (presented == null || presented.isEmpty()) {
      return Optional.empty();
    }
    return constantTimeLookup(apiKeyByDigest, digest(presented));
  }

  @Override
  public Optional<PrincipalRecord> resolveBasic(String identifier, String verifier) {
    if (identifier == null || verifier == null || identifier.isEmpty() || verifier.isEmpty()) {
      return Optional.empty();
    }
    return constantTimeLookup(basicByDigest, digest(identifier + ":" + verifier));
  }

  private Optional<PrincipalRecord> constantTimeLookup(
      Map<String, PrincipalRecord> table, String presentedDigest) {
    byte[] presented = presentedDigest.getBytes(StandardCharsets.UTF_8);
    PrincipalRecord found = null;
    for (Map.Entry<String, PrincipalRecord> entry : table.entrySet()) {
      byte[] stored = entry.getKey().getBytes(StandardCharsets.UTF_8);
      // constant-time byte compare; branch-free match accumulation
      if (MessageDigest.isEqual(presented, stored)) {
        found = entry.getValue();
      }
    }
    return Optional.ofNullable(found);
  }

  private static String digest(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
