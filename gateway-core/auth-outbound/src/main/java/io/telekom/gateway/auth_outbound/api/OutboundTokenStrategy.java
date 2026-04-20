/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.auth_outbound.api;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Strategy that produces the outbound {@code Authorization} header for a single exchange.
 *
 * <p>Implementations read the supplied {@link OutboundAuthPolicy}, obtain or mint the credential,
 * and mutate the request on the given {@link ServerWebExchange}. See {@code README.md} for the five
 * supported policy types and the prose recipe each strategy follows.
 */
public interface OutboundTokenStrategy {

  Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy);
}
