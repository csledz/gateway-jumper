// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.api;

import reactor.core.publisher.Mono;

/**
 * Evaluates a {@link Policy} against a {@link PolicyContext} and returns a {@link PolicyDecision}.
 */
public interface PolicyEvaluator {

  /** Which {@link Policy.Language} this evaluator implements. */
  Policy.Language language();

  /**
   * Evaluate {@code policy} against {@code ctx}. Implementations MUST NOT block the calling thread;
   * expensive work should be scheduled on an appropriate {@link reactor.core.scheduler.Scheduler}.
   */
  Mono<PolicyDecision> evaluate(PolicyContext ctx, Policy policy);
}
