// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.spel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.telekom.gateway.policy_engine.api.Policy;
import io.telekom.gateway.policy_engine.api.PolicyContext;
import io.telekom.gateway.policy_engine.api.PolicyDecision;
import io.telekom.gateway.policy_engine.api.PolicyEvaluator;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * SpEL-based {@link PolicyEvaluator}. The {@link PolicyContext} is exposed as the root object so
 * policies can reference its accessors directly, e.g.:
 *
 * <pre>
 *   hasScope('read') and claim('tenant') == 'acme'
 * </pre>
 *
 * <p>A policy expression may evaluate to a {@link Boolean} (plain allow/deny) or to a {@link Map}
 * with a required {@code allowed} boolean and optional {@code reason} + {@code obligations} keys,
 * which lets a single expression emit obligations alongside its decision.
 *
 * <p>Parsed expressions are cached keyed on the policy source text.
 */
@Slf4j
@Component
public class SpelPolicyEvaluator implements PolicyEvaluator {

  private final ExpressionParser parser = new SpelExpressionParser();
  private final Cache<String, Expression> cache = Caffeine.newBuilder().maximumSize(1_024).build();

  @Override
  public Policy.Language language() {
    return Policy.Language.SPEL;
  }

  @Override
  public Mono<PolicyDecision> evaluate(PolicyContext ctx, Policy policy) {
    return Mono.fromCallable(() -> evaluateSync(ctx, policy))
        .onErrorResume(
            ex -> {
              log.warn("SpEL policy '{}' failed: {}", policy.name(), ex.toString());
              return Mono.just(PolicyDecision.deny("evaluation_error: " + ex.getMessage()));
            });
  }

  private PolicyDecision evaluateSync(PolicyContext ctx, Policy policy) {
    Expression expr = cache.get(policy.source(), parser::parseExpression);
    EvaluationContext ec =
        SimpleEvaluationContext.forReadOnlyDataBinding()
            .withInstanceMethods()
            .withRootObject(ctx)
            .build();
    Object result = expr.getValue(ec, ctx);
    return asDecision(result, policy);
  }

  @SuppressWarnings("unchecked")
  private static PolicyDecision asDecision(Object result, Policy policy) {
    if (result instanceof Boolean b) {
      return b ? PolicyDecision.allow() : PolicyDecision.deny("policy:" + policy.name());
    }
    if (result instanceof Map<?, ?> raw) {
      Map<String, Object> map = (Map<String, Object>) raw;
      Object allowed = map.get("allowed");
      boolean allow = allowed instanceof Boolean b && b;
      String reason = map.get("reason") instanceof String s ? s : (allow ? "allow" : "deny");
      Map<String, Object> obligations = new LinkedHashMap<>();
      Object o = map.get("obligations");
      if (o instanceof Map<?, ?> om) {
        om.forEach((k, v) -> obligations.put(String.valueOf(k), v));
      }
      return new PolicyDecision(allow, reason, obligations);
    }
    if (result == null) {
      return PolicyDecision.deny("policy:" + policy.name() + ":null");
    }
    return PolicyDecision.deny(
        "policy:" + policy.name() + ":unexpected-type:" + result.getClass().getSimpleName());
  }
}
