// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.metrics;

import io.telekom.gateway.observability.ObservabilityConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Netty server-level metrics and scopes the per-URI tag to a single prefix to avoid
 * cardinality explosions. Ported from jumper's {@code NettyMetricsConfig}, but the path is kept
 * generic rather than Kong-specific.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class NettyServerMetrics {

  @Bean
  public NettyServerCustomizer nettyMetricsServerCustomizer() {
    return httpServer -> {
      log.info(
          "NettyServerCustomizer applied (metrics enabled, uri tag scoped to {})",
          ObservabilityConstants.GATEWAY_ROOT_PATH_PREFIX);
      return httpServer.metrics(true, uri -> ObservabilityConstants.GATEWAY_ROOT_PATH_PREFIX);
    };
  }
}
