// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.api.InboundAuthenticator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class InboundAuthFilterTest {

  @Test
  void passesThroughWhenRouteDoesNotRequireAuth() {
    InboundAuthFilter filter = new InboundAuthFilter(Map.of(), exchange -> Optional.empty());
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
    assertThat(exchange.getAttributes()).doesNotContainKey(InboundAuthFilter.AUTH_CONTEXT_ATTR);
  }

  @Test
  void publishesAuthContextOnSuccess() {
    InboundAuthenticator good =
        ex ->
            Mono.just(
                new AuthContext(
                    "u-1", "issuer", Map.of(), Set.of("read"), AuthContext.Type.JWT, "trace"));
    EnumMap<AuthContext.Type, InboundAuthenticator> by = new EnumMap<>(AuthContext.Type.class);
    by.put(AuthContext.Type.JWT, good);
    InboundAuthFilter filter =
        new InboundAuthFilter(by, exchange -> Optional.of(AuthContext.Type.JWT));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/r").header("X-Auth-Type", "jwt"));

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
    AuthContext ctx =
        (AuthContext) exchange.getAttributes().get(InboundAuthFilter.AUTH_CONTEXT_ATTR);
    assertThat(ctx).isNotNull();
    assertThat(ctx.principalId()).isEqualTo("u-1");
  }

  @Test
  void rejectsWith401WhenAuthenticatorEmpty() {
    InboundAuthenticator empty = ex -> Mono.empty();
    EnumMap<AuthContext.Type, InboundAuthenticator> by = new EnumMap<>(AuthContext.Type.class);
    by.put(AuthContext.Type.APIKEY, empty);
    InboundAuthFilter filter =
        new InboundAuthFilter(by, exchange -> Optional.of(AuthContext.Type.APIKEY));
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));

    filter.filter(exchange, ex -> Mono.error(new AssertionError("chain must not run"))).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void headerTypeResolverParsesX_Auth_Type() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/r").header("X-Auth-Type", "basic"));
    Optional<AuthContext.Type> resolved = InboundAuthFilter.headerTypeResolver().apply(exchange);
    assertThat(resolved).contains(AuthContext.Type.BASIC);
  }
}
