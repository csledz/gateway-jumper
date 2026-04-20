// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import java.util.EnumMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class OutboundAuthFilterTest {

  @Test
  void dispatchesToMatchingStrategyAndPassesChain() {
    EnumMap<OutboundAuthPolicy.Type, OutboundTokenStrategy> strategies =
        new EnumMap<>(OutboundAuthPolicy.Type.class);
    strategies.put(
        OutboundAuthPolicy.Type.BASIC,
        (exchange, policy) -> {
          exchange
              .getAttributes()
              .put(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR, "Basic AAAA");
          return Mono.empty();
        });

    OutboundAuthPolicy policy =
        new OutboundAuthPolicy(
            OutboundAuthPolicy.Type.BASIC, "c", null, null, null, null, null, null, "svc");
    OutboundAuthFilter filter = new OutboundAuthFilter(strategies, ex -> Optional.of(policy));
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    boolean[] hit = new boolean[1];
    filter
        .filter(
            exchange,
            ex -> {
              hit[0] = true;
              return Mono.empty();
            })
        .block();

    assertThat(hit[0]).isTrue();
    assertThat(exchange.getAttributes().get(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR))
        .isEqualTo("Basic AAAA");
  }

  @Test
  void noPolicyPassesChainUnchanged() {
    OutboundAuthFilter filter =
        new OutboundAuthFilter(
            new EnumMap<>(OutboundAuthPolicy.Type.class), ex -> Optional.empty());
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    boolean[] hit = new boolean[1];
    filter
        .filter(
            exchange,
            ex -> {
              hit[0] = true;
              return Mono.empty();
            })
        .block();

    assertThat(hit[0]).isTrue();
  }

  @Test
  void missingStrategyForTypePassesChainWithWarnLog() {
    OutboundAuthFilter filter =
        new OutboundAuthFilter(
            new EnumMap<>(OutboundAuthPolicy.Type.class),
            ex ->
                Optional.of(
                    new OutboundAuthPolicy(
                        OutboundAuthPolicy.Type.ONE_TOKEN,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    boolean[] hit = new boolean[1];
    filter
        .filter(
            exchange,
            ex -> {
              hit[0] = true;
              return Mono.empty();
            })
        .block();

    assertThat(hit[0]).isTrue();
  }
}
