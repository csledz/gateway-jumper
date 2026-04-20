// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.api;

import java.util.Map;

/**
 * Outcome of a policy evaluation.
 *
 * <p>Obligations are side-effects the filter MUST apply when {@code allowed == true}. Supported
 * keys are documented in {@code README.md}; notably {@code add_header:<Name>} adds a header and
 * {@code log} emits a log record.
 */
public record PolicyDecision(boolean allowed, String reason, Map<String, Object> obligations) {

  public PolicyDecision {
    obligations = obligations == null ? Map.of() : Map.copyOf(obligations);
    reason = reason == null ? "" : reason;
  }

  public static PolicyDecision allow() {
    return new PolicyDecision(true, "allow", Map.of());
  }

  public static PolicyDecision allow(Map<String, Object> obligations) {
    return new PolicyDecision(true, "allow", obligations);
  }

  public static PolicyDecision deny(String reason) {
    return new PolicyDecision(false, reason, Map.of());
  }
}
