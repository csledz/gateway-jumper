// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.onetoken;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import io.telekom.gateway.auth_outbound.crypto.RsaKeyLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class OneTokenStrategyTest {

  @Test
  void signsJwtWithInjectedClaims(@TempDir Path tempDir)
      throws NoSuchAlgorithmException, IOException {
    KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path keyFile = tempDir.resolve("tls.key");
    Path kidFile = tempDir.resolve("tls.kid");
    Files.write(keyFile, pair.getPrivate().getEncoded());
    Files.writeString(kidFile, "kid-test\n");

    RsaKeyLoader keys = new RsaKeyLoader(keyFile, kidFile);
    OneTokenStrategy strategy = new OneTokenStrategy(keys, "sg-edge", "zone-a");

    OutboundAuthPolicy policy =
        new OutboundAuthPolicy(
            OutboundAuthPolicy.Type.ONE_TOKEN,
            "client-1",
            null,
            null,
            null,
            List.of(),
            "realm-x",
            "env-prod",
            "svc-y");

    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/widgets/123"));

    strategy.apply(exchange, policy).block();

    String header =
        (String) exchange.getAttributes().get(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR);
    assertThat(header).startsWith("Bearer ");
    String compact = header.substring("Bearer ".length());

    var parsed = Jwts.parser().verifyWith(pair.getPublic()).build().parseSignedClaims(compact);
    assertThat(parsed.getPayload().get("originZone")).isEqualTo("zone-a");
    assertThat(parsed.getPayload().get("originStargate")).isEqualTo("sg-edge");
    assertThat(parsed.getPayload().get("clientId")).isEqualTo("client-1");
    assertThat(parsed.getPayload().get("operation")).isEqualTo("GET");
    assertThat(parsed.getPayload().get("requestPath")).isEqualTo("/widgets/123");
    assertThat(parsed.getPayload().get("env")).isEqualTo("env-prod");
    assertThat(parsed.getHeader().get("kid")).isEqualTo("kid-test");
  }
}
