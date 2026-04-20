// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.policy_engine.api.Policy;
import io.telekom.gateway.policy_engine.api.PolicyContext;
import io.telekom.gateway.policy_engine.api.PolicyDecision;
import io.telekom.gateway.policy_engine.api.PolicyEvaluator;
import io.telekom.gateway.policy_engine.filter.PolicyFilter;
import io.telekom.gateway.policy_engine.registry.PolicyRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class PolicySteps {

  @Autowired private PolicyRegistry registry;
  @Autowired private List<PolicyEvaluator> evaluators;
  @Autowired private PolicyFilter filter;

  // Scenario state
  private Policy policy;
  private String principalId;
  private List<String> scopes = new ArrayList<>();
  private final Map<String, Object> claims = new LinkedHashMap<>();
  private String method = "GET";
  private String path = "/";
  private final Map<String, List<String>> headers = new LinkedHashMap<>();
  private PolicyDecision decision;
  private ServerWebExchange capturedDownstream;
  private boolean chainProceeded;

  @Before
  public void reset() {
    policy = null;
    principalId = null;
    scopes = new ArrayList<>();
    claims.clear();
    method = "GET";
    path = "/";
    headers.clear();
    decision = null;
    capturedDownstream = null;
    chainProceeded = false;
  }

  @Given("a SpEL policy {string} with source {string}")
  public void a_spel_policy(String name, String source) {
    policy = new Policy(name, Policy.Language.SPEL, source);
    registry.register(policy);
  }

  @Given("a Rego policy {string} loaded from {string}")
  public void a_rego_policy(String name, String classpathResource) throws IOException {
    String source = readClasspath(classpathResource);
    policy = new Policy(name, Policy.Language.REGO, source);
    registry.register(policy);
  }

  @And("a request with scopes {string} and method {string} on path {string}")
  public void request_with_scopes_method_path(String scopesCsv, String m, String p) {
    this.scopes = splitCsv(scopesCsv);
    this.method = m;
    this.path = p;
  }

  @And("a request with claim {string} set to {string} and method {string} on path {string}")
  public void request_with_claim_method_path(String key, String value, String m, String p) {
    claims.put(key, value);
    this.method = m;
    this.path = p;
  }

  @And(
      "a request with scopes {string} and claim {string} set to {string} and method {string} on"
          + " path {string}")
  public void request_with_scopes_and_claim(
      String scopesCsv, String key, String value, String m, String p) {
    this.scopes = splitCsv(scopesCsv);
    claims.put(key, value);
    this.method = m;
    this.path = p;
  }

  @When("the policy is evaluated")
  public void the_policy_is_evaluated() {
    PolicyEvaluator evaluator =
        evaluators.stream()
            .filter(e -> e.language() == policy.language())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no evaluator for " + policy.language()));
    PolicyContext ctx = buildContext();
    decision = evaluator.evaluate(ctx, policy).block();
  }

  @When("the policy is evaluated through the filter")
  public void the_policy_is_evaluated_through_the_filter() {
    MockServerHttpRequest.BaseBuilder<?> reqBuilder =
        MockServerHttpRequest.method(HttpMethod.valueOf(method), path);
    headers.forEach((k, v) -> reqBuilder.header(k, v.toArray(new String[0])));
    MockServerWebExchange exchange = MockServerWebExchange.from(reqBuilder.build());
    exchange.getAttributes().put(PolicyFilter.POLICY_REF_ATTR, policy.name());
    exchange.getAttributes().put("gateway.policy.claims", claims);
    exchange.getAttributes().put("gateway.policy.scopes", scopes);
    if (principalId != null) {
      exchange.getAttributes().put("gateway.policy.principal", principalId);
    }

    GatewayFilterChain chain =
        ex -> {
          capturedDownstream = ex;
          chainProceeded = true;
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    // On deny the chain is not called; the decision object isn't populated here, so we reuse
    // the response for assertions via the downstream exchange / headers.
    if (!chainProceeded) {
      decision =
          PolicyDecision.deny(
              String.valueOf(
                  exchange.getResponse().getHeaders().getFirst(PolicyFilter.POLICY_REASON_HEADER)));
    } else {
      decision = PolicyDecision.allow();
    }
  }

  @Then("the decision is allow")
  public void the_decision_is_allow() {
    assertThat(decision).isNotNull();
    assertThat(decision.allowed()).isTrue();
  }

  @Then("the decision is deny")
  public void the_decision_is_deny() {
    assertThat(decision).isNotNull();
    assertThat(decision.allowed()).isFalse();
  }

  @Then("the decision is deny with reason {string}")
  public void the_decision_is_deny_with_reason(String reason) {
    assertThat(decision).isNotNull();
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo(reason);
  }

  @Then("the upstream request contains header {string} with value {string}")
  public void the_upstream_request_contains_header(String name, String value) {
    assertThat(capturedDownstream).as("chain must have been called").isNotNull();
    HttpHeaders h = capturedDownstream.getRequest().getHeaders();
    assertThat(h.getFirst(name)).isEqualTo(value);
  }

  @Then("the filter chain proceeds")
  public void the_filter_chain_proceeds() {
    assertThat(chainProceeded).isTrue();
  }

  // -- helpers ----------------------------------------------------------

  private PolicyContext buildContext() {
    return new PolicyContext(principalId, scopes, claims, method, path, headers);
  }

  private static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    return new ArrayList<>(Arrays.asList(csv.split("\\s*,\\s*")));
  }

  private static String readClasspath(String resource) throws IOException {
    try (InputStream is = PolicySteps.class.getClassLoader().getResourceAsStream(resource)) {
      if (is == null) {
        throw new IOException("resource not found on classpath: " + resource);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
