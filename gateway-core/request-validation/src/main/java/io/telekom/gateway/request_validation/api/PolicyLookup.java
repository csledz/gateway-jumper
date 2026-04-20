// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.api;

import java.util.Optional;
import org.springframework.web.server.ServerWebExchange;

/**
 * Resolves the {@link ValidationPolicy} that applies to the current exchange. The default
 * implementation returns an empty optional (no policy attached); the controller module will supply
 * a real implementation that reads from the aggregated config snapshot.
 */
@FunctionalInterface
public interface PolicyLookup {
  Optional<ValidationPolicy> find(ServerWebExchange exchange);
}
