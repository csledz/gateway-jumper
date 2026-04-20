// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.api;

import java.net.URI;
import java.util.Objects;

/**
 * A concrete, resolved upstream endpoint.
 *
 * @param host hostname or IP
 * @param port TCP port
 * @param scheme transport scheme ({@code http}/{@code https})
 * @param healthy whether the endpoint is currently considered healthy
 * @param weight relative weight for weighted round-robin (>= 0; 0 = skip)
 */
public record ServiceEndpoint(String host, int port, String scheme, boolean healthy, int weight) {

  public ServiceEndpoint {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(scheme, "scheme");
    if (port <= 0) {
      throw new IllegalArgumentException("port must be > 0, got " + port);
    }
    if (weight < 0) {
      throw new IllegalArgumentException("weight must be >= 0, got " + weight);
    }
  }

  /** Build a healthy endpoint with default weight {@code 1}. */
  public static ServiceEndpoint of(String host, int port, String scheme) {
    return new ServiceEndpoint(host, port, scheme, true, 1);
  }

  /** Render as a URI {@code scheme://host:port}. */
  public URI toUri() {
    return URI.create(scheme + "://" + host + ":" + port);
  }
}
