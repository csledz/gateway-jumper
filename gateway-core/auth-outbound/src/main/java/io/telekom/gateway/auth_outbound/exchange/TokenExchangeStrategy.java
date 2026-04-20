// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.exchange;

import io.telekom.gateway.auth_outbound.api.OutboundAuthPolicy;
import io.telekom.gateway.auth_outbound.api.OutboundTokenStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Passes through the value of the {@code X-Token-Exchange} request header as the outbound
 * Authorization. Intended only for internet-facing zones; other strategies override this one in
 * mesh flows.
 */
@Slf4j
public class TokenExchangeStrategy implements OutboundTokenStrategy {

  public static final String HEADER = "X-Token-Exchange";

  @Override
  public Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy) {
    String value = exchange.getRequest().getHeaders().getFirst(HEADER);
    if (value == null || value.isBlank()) {
      log.warn("X-Token-Exchange header missing for TOKEN_EXCHANGE policy");
      return Mono.empty();
    }
    String header = value.startsWith("Bearer ") ? value : "Bearer " + value;
    exchange.getAttributes().put(OutboundTokenStrategy.OUTBOUND_AUTH_HEADER_ATTR, header);
    return Mono.empty();
  }
}
