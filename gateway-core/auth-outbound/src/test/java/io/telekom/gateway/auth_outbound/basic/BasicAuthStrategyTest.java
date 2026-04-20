// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.auth_outbound.api.BasicCredentialResolver;
import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class BasicAuthStrategyTest {

  @Test
  void encodesUsernameColonPassword() {
    BasicCredentialResolver resolver =
        policy -> Optional.of(new BasicCredentialResolver.UsernamePassword("alice", "open"));
    BasicAuthStrategy strategy = new BasicAuthStrategy(resolver);
    OutboundAuthPolicy policy =
        new OutboundAuthPolicy(
            OutboundAuthPolicy.Type.BASIC, null, null, null, null, null, null, null, "svc");
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    strategy.apply(exchange, policy).block();

    String expected =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:open".getBytes(StandardCharsets.UTF_8));
    assertThat(exchange.getAttributes().get(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR))
        .isEqualTo(expected);
  }

  @Test
  void skipsQuietlyWhenResolverEmpty() {
    BasicAuthStrategy strategy = new BasicAuthStrategy(policy -> Optional.empty());
    OutboundAuthPolicy policy =
        new OutboundAuthPolicy(
            OutboundAuthPolicy.Type.BASIC, null, null, null, null, null, null, null, "svc");
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    strategy.apply(exchange, policy).block();

    assertThat(exchange.getAttributes())
        .doesNotContainKey(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR);
  }
}
