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

  /**
   * Exchange attribute where strategies publish the outbound {@code Authorization} header value.
   * The proxy module's downstream forwarder reads this attribute and applies it to the upstream
   * request just before dispatch, so strategies never have to mutate the incoming request.
   */
  String OUTBOUND_AUTH_HEADER_ATTR = "io.telekom.gateway.outboundAuthHeader";

  /**
   * Exchange attribute where Mesh strategy publishes the original consumer token so the proxy
   * forwards it in {@code X-Consumer-Token} to the peer.
   */
  String CONSUMER_TOKEN_ATTR = "io.telekom.gateway.consumerToken";

  Mono<Void> apply(ServerWebExchange exchange, OutboundAuthPolicy policy);
}
