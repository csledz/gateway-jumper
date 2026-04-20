// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.auth_inbound.api;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Public surface for an inbound authenticator.
 *
 * <p>Skeleton only. See README for the implementation recipe.
 */
public interface InboundAuthenticator {

  /** Authenticate the given exchange and return an {@link AuthContext}. */
  Mono<AuthContext> authenticate(ServerWebExchange exchange);
}
