/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.example;

import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import io.telekom.gateway.plugin_spi.api.PipelineStage;
import io.telekom.gateway.plugin_spi.api.PluginContext;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Example plugin: ensures every request carries an {@code X-Request-Id} header, and mirrors the
 * value onto the response.
 *
 * <p>Wired via {@code META-INF/services/io.telekom.gateway.plugin_spi.api.GatewayPlugin}.
 */
@Slf4j
public class XRequestIdPlugin implements GatewayPlugin {

  public static final String HEADER = "X-Request-Id";
  public static final String ATTR = "io.telekom.gateway.plugin_spi.example.x-request-id";

  @Override
  public String name() {
    return "x-request-id";
  }

  @Override
  public int order() {
    return 100; // infra plugin: runs before user plugins (default 1000)
  }

  @Override
  public PipelineStage stage() {
    return PipelineStage.PRE_ROUTING;
  }

  @Override
  public Mono<Void> apply(PluginContext context) {
    String existing = context.requestHeaders().getFirst(HEADER);
    String value;
    if (existing == null || existing.isBlank()) {
      value = UUID.randomUUID().toString();
      context.requestHeaders().set(HEADER, value);
      log.debug("Generated {}: {}", HEADER, value);
    } else {
      value = existing;
    }
    context.putAttribute(ATTR, value);
    context.responseHeaders().set(HEADER, value);
    return Mono.empty();
  }
}
