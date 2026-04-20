// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.onetoken;

import io.jsonwebtoken.Jwts;
import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import io.telekom.gateway.auth_outbound.crypto.RsaKeyLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Signs a new RS256 JWT with claim injection. Ported from jumper's TokenGeneratorService — identity
 * derives from the exchange's inbound {@code AuthContext}-populated attributes and the policy's
 * {@code realm}/{@code environment}/{@code serviceOwner}.
 */
@Slf4j
public class OneTokenStrategy implements OutboundTokenStrategy {

  public static final String INBOUND_PRINCIPAL_ATTR = "io.telekom.gateway.authContext";
  private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

  private final RsaKeyLoader keys;
  private final String localStargate;
  private final String localZone;

  public OneTokenStrategy(RsaKeyLoader keys, String localStargate, String localZone) {
    this.keys = keys;
    this.localStargate = localStargate;
    this.localZone = localZone;
  }

  @Override
  public Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy) {
    String principal = resolvePrincipal(exchange);
    Instant now = Instant.now();
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", principal);
    claims.put("clientId", policy.clientId());
    claims.put("originZone", localZone);
    claims.put("originStargate", localStargate);
    claims.put(
        "operation",
        exchange.getRequest().getMethod() == null
            ? HttpMethod.GET.name()
            : exchange.getRequest().getMethod().name());
    claims.put("requestPath", exchange.getRequest().getPath().toString());
    claims.put("env", policy.environment());
    claims.put("realm", policy.realm());
    claims.put("serviceOwner", policy.serviceOwner());

    String compact =
        Jwts.builder()
            .header()
            .keyId(keys.kid())
            .type("JWT")
            .and()
            .claims(claims)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(DEFAULT_TTL)))
            .signWith(keys.privateKey(), Jwts.SIG.RS256)
            .compact();

    exchange
        .getAttributes()
        .put(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR, "Bearer " + compact);
    return Mono.empty();
  }

  private String resolvePrincipal(ServerWebExchange exchange) {
    Object attr = exchange.getAttributes().get(INBOUND_PRINCIPAL_ATTR);
    if (attr != null) {
      try {
        var m = attr.getClass().getMethod("principalId");
        Object value = m.invoke(attr);
        if (value instanceof String s && !s.isBlank()) {
          return s;
        }
      } catch (ReflectiveOperationException ignore) {
        // fall through to unknown
      }
    }
    return "unknown";
  }
}
