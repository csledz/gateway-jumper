// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.basic;

import io.telekom.gateway.auth_outbound.api.BasicCredentialResolver;
import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Sets {@code Authorization: Basic <base64(user:pass)>} using a pair from {@link
 * BasicCredentialResolver}.
 */
@Slf4j
public class BasicAuthStrategy implements OutboundTokenStrategy {

  private final BasicCredentialResolver resolver;

  public BasicAuthStrategy(BasicCredentialResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy) {
    return resolver
        .resolve(policy)
        .map(
            pair -> {
              String encoded =
                  Base64.getEncoder()
                      .encodeToString(
                          (pair.username() + ":" + pair.password())
                              .getBytes(StandardCharsets.UTF_8));
              exchange
                  .getAttributes()
                  .put(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR, "Basic " + encoded);
              return Mono.<Void>empty();
            })
        .orElseGet(
            () -> {
              log.warn("Basic resolver returned empty for serviceOwner={}", policy.serviceOwner());
              return Mono.empty();
            });
  }
}
