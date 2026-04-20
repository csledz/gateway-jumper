// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.store.InMemoryCredentialStore;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ApiKeyAuthenticatorTest {

  private final InMemoryCredentialStore store =
      new InMemoryCredentialStore()
          .registerApiKey("known-uuid-1", "svc-a", Set.of("read", "write"));
  private final ApiKeyAuthenticator authenticator =
      new ApiKeyAuthenticator(store, "X-API-Key", "apikey");

  @Test
  void acceptsValidHeaderKey() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/r").header("X-API-Key", "known-uuid-1"));

    AuthContext ctx = authenticator.authenticate(exchange).block();

    assertThat(ctx).isNotNull();
    assertThat(ctx.principalId()).isEqualTo("svc-a");
    assertThat(ctx.type()).isEqualTo(AuthContext.Type.APIKEY);
    assertThat(ctx.scopes()).containsExactlyInAnyOrder("read", "write");
  }

  @Test
  void fallsBackToQueryParam() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/r?apikey=known-uuid-1"));

    AuthContext ctx = authenticator.authenticate(exchange).block();

    assertThat(ctx).isNotNull();
    assertThat(ctx.principalId()).isEqualTo("svc-a");
  }

  @Test
  void rejectsUnknownKey() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/r").header("X-API-Key", "bogus"));
    assertThat(authenticator.authenticate(exchange).block()).isNull();
  }

  @Test
  void ignoresMissingKey() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));
    assertThat(authenticator.authenticate(exchange).block()).isNull();
  }
}
