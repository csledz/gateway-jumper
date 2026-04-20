// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.api;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Pluggable resolver contract. Implementations are expected to be non-blocking; any blocking I/O
 * (e.g. {@link java.net.InetAddress#getAllByName}) MUST be wrapped on {@code
 * reactor.core.scheduler.Schedulers.boundedElastic()}.
 */
public interface ServiceResolver {

  /** The scheme this resolver handles (e.g. {@code k8s}, {@code dns}, {@code consul}). */
  String scheme();

  /**
   * Resolve a {@link ServiceRef} to zero or more {@link ServiceEndpoint}s. Empty list means "no
   * currently healthy endpoints"; the caller decides whether to fail the request or retry.
   */
  Mono<List<ServiceEndpoint>> resolve(ServiceRef ref);
}
