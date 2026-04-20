// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.rego;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.telekom.gateway.policy_engine.api.Policy;
import io.telekom.gateway.policy_engine.api.PolicyContext;
import io.telekom.gateway.policy_engine.api.PolicyDecision;
import io.telekom.gateway.policy_engine.api.PolicyEvaluator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Adapter for Rego policies. Loads modules lazily keyed on the policy source text.
 *
 * <p>The real OPA evaluator is plugged in when {@code com.styra:opa} (or a compatible embedded
 * implementation) is on the classpath — activated via the {@code rego} Maven profile. When the
 * dependency is absent the bean still exposes a tiny built-in evaluator sufficient for basic {@code
 * package ... default allow := false ... allow { ... }} rules; this keeps the module self-contained
 * for dev / tests and fails closed for unsupported constructs.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.policy", name = "rego.enabled", havingValue = "true")
public class RegoPolicyEvaluator implements PolicyEvaluator {

  private final Cache<String, CompiledModule> cache =
      Caffeine.newBuilder().maximumSize(1_024).build();

  @Override
  public Policy.Language language() {
    return Policy.Language.REGO;
  }

  @Override
  public Mono<PolicyDecision> evaluate(PolicyContext ctx, Policy policy) {
    return Mono.fromCallable(() -> evaluateSync(ctx, policy))
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(
            ex -> {
              log.warn("Rego policy '{}' failed: {}", policy.name(), ex.toString());
              return Mono.just(PolicyDecision.deny("evaluation_error: " + ex.getMessage()));
            });
  }

  private PolicyDecision evaluateSync(PolicyContext ctx, Policy policy) {
    CompiledModule module =
        cache.get(policy.source(), src -> CompiledModule.parse(policy.name(), src));
    return module.evaluate(ctx);
  }

  /**
   * Minimal Rego subset. Supports:
   *
   * <ul>
   *   <li>{@code default allow := false} / {@code default allow = false}
   *   <li>{@code allow { <expr>; <expr> }} rule bodies — all exprs must be truthy.
   *   <li>{@code input.scopes[_] == "read"} — membership test.
   *   <li>{@code input.method == "GET"} / {@code input.path == "/x"} / {@code input.claims.tenant
   *       == "acme"}
   *   <li>{@code input.headers["X-Tenant"] == "acme"}
   * </ul>
   */
  static final class CompiledModule {
    private static final Pattern DEFAULT_ALLOW =
        Pattern.compile("default\\s+allow\\s*(?::=|=)\\s*(true|false)");
    private static final Pattern ALLOW_BLOCK =
        Pattern.compile("allow\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    private static final Pattern SCOPE_MEMBERSHIP =
        Pattern.compile("input\\.scopes\\[_]\\s*==\\s*\"([^\"]+)\"");
    private static final Pattern EQUALITY =
        Pattern.compile(
            "(input(?:\\.[a-zA-Z_][a-zA-Z0-9_]*|\\[\"[^\"]+\"])+)\\s*==\\s*\"([^\"]+)\"");
    private static final Pattern REF_TOKEN =
        Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)|\\[\"([^\"]+)\"]");

    private final String name;
    private final boolean defaultAllow;
    private final List<List<String>> allowBlocks;

    private CompiledModule(String name, boolean defaultAllow, List<List<String>> allowBlocks) {
      this.name = name;
      this.defaultAllow = defaultAllow;
      this.allowBlocks = allowBlocks;
    }

    static CompiledModule parse(String name, String src) {
      String stripped = stripComments(src);
      Matcher d = DEFAULT_ALLOW.matcher(stripped);
      boolean defaultAllow = d.find() && Boolean.parseBoolean(d.group(1));
      List<List<String>> blocks = new ArrayList<>();
      Matcher b = ALLOW_BLOCK.matcher(stripped);
      while (b.find()) {
        List<String> exprs = new ArrayList<>();
        for (String line : b.group(1).split("[;\\n]")) {
          String l = line.trim();
          if (!l.isEmpty()) {
            exprs.add(l);
          }
        }
        blocks.add(exprs);
      }
      return new CompiledModule(name, defaultAllow, blocks);
    }

    private static String stripComments(String src) {
      StringBuilder out = new StringBuilder(src.length());
      for (String line : src.split("\n")) {
        int i = line.indexOf('#');
        out.append(i >= 0 ? line.substring(0, i) : line).append('\n');
      }
      return out.toString();
    }

    PolicyDecision evaluate(PolicyContext ctx) {
      Map<String, Object> input = toInput(ctx);
      for (List<String> block : allowBlocks) {
        if (block.stream().allMatch(e -> evalExpr(e, input))) {
          return new PolicyDecision(true, "allow:" + name, Map.of());
        }
      }
      return defaultAllow
          ? new PolicyDecision(true, "default_allow:" + name, Map.of())
          : PolicyDecision.deny("policy:" + name);
    }

    private static Map<String, Object> toInput(PolicyContext ctx) {
      Map<String, Object> in = new LinkedHashMap<>();
      in.put("principalId", ctx.principalId());
      in.put("scopes", ctx.scopes());
      in.put("claims", ctx.claims());
      in.put("method", ctx.method());
      in.put("path", ctx.path());
      Map<String, Object> flat = new LinkedHashMap<>();
      ctx.headers().forEach((k, v) -> flat.put(k, v.isEmpty() ? null : v.get(0)));
      in.put("headers", flat);
      return in;
    }

    private static boolean evalExpr(String expr, Map<String, Object> input) {
      Matcher m = SCOPE_MEMBERSHIP.matcher(expr);
      if (m.matches()) {
        Object scopes = input.get("scopes");
        return scopes instanceof List<?> l && l.contains(m.group(1));
      }
      m = EQUALITY.matcher(expr);
      if (m.matches()) {
        return m.group(2).equals(String.valueOf(resolve(m.group(1), input)));
      }
      return false;
    }

    private static Object resolve(String ref, Map<String, Object> input) {
      Object cur = input;
      Matcher tok = REF_TOKEN.matcher(ref);
      while (tok.find()) {
        String key = tok.group(1) != null ? tok.group(1) : tok.group(2);
        if (!(cur instanceof Map<?, ?> mm)) {
          return null;
        }
        cur = mm.get(key);
      }
      return cur;
    }
  }
}
