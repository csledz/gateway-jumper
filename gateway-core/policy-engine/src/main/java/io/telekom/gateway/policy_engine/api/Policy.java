// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.api;

/**
 * A named policy. {@code source} is the SpEL expression for {@link Language#SPEL} policies, or the
 * Rego module text / module ref for {@link Language#REGO} policies.
 */
public record Policy(String name, Language language, String source) {

  public Policy {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("policy name must not be blank");
    }
    if (language == null) {
      throw new IllegalArgumentException("policy language must not be null");
    }
    if (source == null) {
      throw new IllegalArgumentException("policy source must not be null");
    }
  }

  /** Supported policy languages. */
  public enum Language {
    /** Spring Expression Language (default). */
    SPEL,
    /** Rego via OPA adapter. */
    REGO
  }
}
