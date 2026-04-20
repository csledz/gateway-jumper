// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.filter;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import io.telekom.gateway.policy_engine.api.Policy;
import io.telekom.gateway.policy_engine.api.PolicyContext;
import io.telekom.gateway.policy_engine.api.PolicyDecision;
import io.telekom.gateway.policy_engine.api.PolicyEvaluator;
import io.telekom.gateway.policy_engine.registry.PolicyRegistry;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that enforces the policy attached to the current route. The policy reference is
 * resolved in priority order:
 *
 * <ol>
 *   <li>Request attribute {@code gateway.policy.ref} (set by upstream filters / tests).
 *   <li>Route metadata key {@code policy}.
 *   <li>Request header {@code X-Policy-Ref} (dev only).
 * </ol>
 *
 * <p>On {@code allowed == false} the filter short-circuits with {@link HttpStatus#FORBIDDEN}, sets
 * {@code X-Policy-Reason}, and writes a small JSON body. On {@code allowed == true} any {@code
 * add_header:*} obligation is applied to the outbound request.
 */
@Slf4j
@Component
public class PolicyFilter implements GlobalFilter, Ordered {

  /** Execution order: POLICY — runs after auth, before rate-limiting / transformation. */
  public static final int POLICY_FILTER_ORDER = 400;

  /** Request attribute name used to pass a policy ref from earlier filters. */
  public static final String POLICY_REF_ATTR = "gateway.policy.ref";

  /** Route metadata key used to reference a policy by name. */
  public static final String POLICY_METADATA_KEY = "policy";

  /** Dev-only header used to override the policy ref. */
  public static final String POLICY_REF_HEADER = "X-Policy-Ref";

  /** Response header written on deny. */
  public static final String POLICY_REASON_HEADER = "X-Policy-Reason";

  /** Request attribute used to inject the JWT claims map from an upstream auth filter. */
  public static final String CLAIMS_ATTR = "gateway.policy.claims";

  /** Request attribute used to inject the effective scope list from an upstream auth filter. */
  public static final String SCOPES_ATTR = "gateway.policy.scopes";

  /** Request attribute used to inject the principal id from an upstream auth filter. */
  public static final String PRINCIPAL_ATTR = "gateway.policy.principal";

  private static final String ADD_HEADER_OBLIGATION = "add_header:";
  private static final String LOG_OBLIGATION = "log";

  private final PolicyRegistry registry;
  private final Map<Policy.Language, PolicyEvaluator> evaluators;

  public PolicyFilter(PolicyRegistry registry, List<PolicyEvaluator> evaluators) {
    this.registry = registry;
    Map<Policy.Language, PolicyEvaluator> map = new EnumMap<>(Policy.Language.class);
    for (PolicyEvaluator e : evaluators) {
      map.putIfAbsent(e.language(), e);
    }
    this.evaluators = Map.copyOf(map);
  }

  @Override
  public int getOrder() {
    return POLICY_FILTER_ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String ref = resolveRef(exchange);
    if (ref == null) {
      return chain.filter(exchange);
    }
    Policy policy = registry.find(ref).orElse(null);
    if (policy == null) {
      log.warn("policy '{}' not found in registry — denying", ref);
      return deny(exchange, "policy_not_found:" + ref);
    }
    PolicyEvaluator evaluator = evaluators.get(policy.language());
    if (evaluator == null) {
      log.warn("no evaluator for language {} — denying", policy.language());
      return deny(exchange, "no_evaluator:" + policy.language());
    }
    PolicyContext ctx = toContext(exchange);
    return evaluator
        .evaluate(ctx, policy)
        .defaultIfEmpty(PolicyDecision.deny("empty_decision"))
        .flatMap(
            decision -> {
              if (!decision.allowed()) {
                return deny(exchange, decision.reason());
              }
              ServerWebExchange mutated = applyObligations(exchange, decision);
              return chain.filter(mutated);
            });
  }

  private static String resolveRef(ServerWebExchange exchange) {
    Object attr = exchange.getAttribute(POLICY_REF_ATTR);
    if (attr instanceof String s && !s.isBlank()) {
      return s;
    }
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route != null && route.getMetadata() != null) {
      Object md = route.getMetadata().get(POLICY_METADATA_KEY);
      if (md instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    String header = exchange.getRequest().getHeaders().getFirst(POLICY_REF_HEADER);
    if (header != null && !header.isBlank()) {
      return header;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static PolicyContext toContext(ServerWebExchange exchange) {
    ServerHttpRequest req = exchange.getRequest();
    Map<String, List<String>> headers = new LinkedHashMap<>();
    req.getHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
    Map<String, Object> claims =
        exchange.getAttribute(CLAIMS_ATTR) instanceof Map<?, ?> m
            ? (Map<String, Object>) m
            : Map.of();
    List<String> scopes =
        exchange.getAttribute(SCOPES_ATTR) instanceof List<?> l
            ? l.stream().map(String::valueOf).toList()
            : List.of();
    String principalId = exchange.getAttribute(PRINCIPAL_ATTR) instanceof String s ? s : null;
    return new PolicyContext(
        principalId, scopes, claims, req.getMethod().name(), req.getPath().value(), headers);
  }

  private Mono<Void> deny(ServerWebExchange exchange, String reason) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.FORBIDDEN);
    HttpHeaders h = response.getHeaders();
    h.set(POLICY_REASON_HEADER, reason);
    h.setContentType(MediaType.APPLICATION_JSON);
    String escaped =
        new String(JsonStringEncoder.getInstance().quoteAsString(reason == null ? "" : reason));
    String body = "{\"error\":\"forbidden\",\"reason\":\"" + escaped + "\"}";
    DataBuffer buf = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buf));
  }

  private ServerWebExchange applyObligations(ServerWebExchange exchange, PolicyDecision decision) {
    Map<String, Object> obligations = decision.obligations();
    if (obligations == null || obligations.isEmpty()) {
      return exchange;
    }
    Map<String, String> headersToAdd = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : obligations.entrySet()) {
      String key = e.getKey();
      if (key.startsWith(ADD_HEADER_OBLIGATION)) {
        headersToAdd.put(
            key.substring(ADD_HEADER_OBLIGATION.length()), String.valueOf(e.getValue()));
      } else if (LOG_OBLIGATION.equals(key)) {
        log.info("policy obligation:log reason={} detail={}", decision.reason(), e.getValue());
      }
    }
    if (headersToAdd.isEmpty()) {
      return exchange;
    }
    ServerHttpRequest mutated =
        exchange.getRequest().mutate().headers(h -> headersToAdd.forEach(h::set)).build();
    return exchange.mutate().request(mutated).build();
  }
}
