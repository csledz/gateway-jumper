// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_inbound.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.auth_inbound.api.AuthContext;
import io.telekom.gateway.auth_inbound.store.InMemoryCredentialStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class BasicAuthAuthenticatorTest {

  private final InMemoryCredentialStore store =
      new InMemoryCredentialStore()
          .registerBasic("alice", "open-sesame", "user-001", Set.of("read"));
  private final BasicAuthAuthenticator authenticator = new BasicAuthAuthenticator(store);

  @Test
  void acceptsValidCredentials() {
    AuthContext ctx = authenticator.authenticate(requestWithBasic("alice", "open-sesame")).block();
    assertThat(ctx).isNotNull();
    assertThat(ctx.principalId()).isEqualTo("user-001");
    assertThat(ctx.type()).isEqualTo(AuthContext.Type.BASIC);
    assertThat(ctx.scopes()).containsExactly("read");
  }

  @Test
  void rejectsBadVerifier() {
    AuthContext ctx = authenticator.authenticate(requestWithBasic("alice", "wrong")).block();
    assertThat(ctx).isNull();
  }

  @Test
  void rejectsUnknownIdentifier() {
    AuthContext ctx = authenticator.authenticate(requestWithBasic("eve", "anything")).block();
    assertThat(ctx).isNull();
  }

  @Test
  void rejectsMalformedHeader() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/r")
                .header(HttpHeaders.AUTHORIZATION, "Basic !!not-base64!!"));
    AuthContext ctx = authenticator.authenticate(exchange).block();
    assertThat(ctx).isNull();
  }

  @Test
  void ignoresMissingAuthorizationHeader() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/r"));
    AuthContext ctx = authenticator.authenticate(exchange).block();
    assertThat(ctx).isNull();
  }

  private MockServerWebExchange requestWithBasic(String identifier, String verifier) {
    String encoded =
        Base64.getEncoder()
            .encodeToString((identifier + ":" + verifier).getBytes(StandardCharsets.UTF_8));
    return MockServerWebExchange.from(
        MockServerHttpRequest.get("/r").header(HttpHeaders.AUTHORIZATION, "Basic " + encoded));
  }
}
