// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class TokenExchangeStrategyTest {

  private final TokenExchangeStrategy strategy = new TokenExchangeStrategy();
  private final OutboundAuthPolicy policy =
      new OutboundAuthPolicy(
          OutboundAuthPolicy.Type.TOKEN_EXCHANGE, null, null, null, null, null, null, null, null);

  @Test
  void passesThroughOpaqueToken() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/r").header(TokenExchangeStrategy.HEADER, "opaque-value"));

    strategy.apply(exchange, policy).block();

    assertThat(exchange.getAttributes().get(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR))
        .isEqualTo("Bearer opaque-value");
  }

  @Test
  void preservesExistingBearerPrefix() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/r").header(TokenExchangeStrategy.HEADER, "Bearer already"));

    strategy.apply(exchange, policy).block();

    assertThat(exchange.getAttributes().get(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR))
        .isEqualTo("Bearer already");
  }

  @Test
  void noopWhenHeaderMissing() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));
    strategy.apply(exchange, policy).block();
    assertThat(exchange.getAttributes())
        .doesNotContainKey(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR);
  }
}
