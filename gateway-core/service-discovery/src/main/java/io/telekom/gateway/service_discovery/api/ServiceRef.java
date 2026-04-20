// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.api;

import java.net.URI;
import java.util.Objects;

/**
 * Abstract reference to an upstream service, decoupled from any concrete resolver implementation.
 *
 * <p>URIs use the scheme to pick the resolver: {@code k8s://<service>.<namespace>:<port>}, {@code
 * dns://<host>:<port>}, {@code consul://<service>}.
 *
 * @param name logical service name (k8s Service name, DNS host, Consul service id)
 * @param namespace k8s namespace (nullable for non-k8s schemes)
 * @param port target port (0 = resolver default)
 * @param scheme one of {@code k8s}, {@code dns}, {@code consul}; controls dispatch
 */
public record ServiceRef(String name, String namespace, int port, String scheme) {

  public ServiceRef {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(scheme, "scheme");
  }

  /** Parse a URI like {@code k8s://my-svc.prod:8080} or {@code dns://example.com:443}. */
  public static ServiceRef fromUri(URI uri) {
    Objects.requireNonNull(uri, "uri");
    String scheme = uri.getScheme();
    String host = uri.getHost();
    int port = uri.getPort() == -1 ? 0 : uri.getPort();
    if (host == null) {
      throw new IllegalArgumentException("uri must have a host: " + uri);
    }
    String name = host;
    String namespace = null;
    if ("k8s".equalsIgnoreCase(scheme)) {
      int dot = host.indexOf('.');
      if (dot > 0) {
        name = host.substring(0, dot);
        namespace = host.substring(dot + 1);
      }
    }
    return new ServiceRef(name, namespace, port, scheme.toLowerCase());
  }
}
