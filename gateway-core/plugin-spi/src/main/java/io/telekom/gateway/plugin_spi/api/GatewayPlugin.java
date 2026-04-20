/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.api;

import reactor.core.publisher.Mono;

/**
 * Service Provider Interface (SPI) contract for a gateway plugin.
 *
 * <p>Plugin authors implement this interface and register the fully-qualified class name in {@code
 * META-INF/services/io.telekom.gateway.plugin_spi.api.GatewayPlugin}. The runtime discovers plugins
 * via {@link java.util.ServiceLoader} from the current classloader and from any JAR dropped into
 * the configured plugin directory.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>Implementations <b>must</b> be thread-safe and idempotent: the same instance handles every
 *       request.
 *   <li>{@link #name()} must be unique across all registered plugins; the loader keeps the
 *       first-seen and warns about duplicates.
 *   <li>{@link #order()} is an {@code int}; lower values run first within a stage. The default is
 *       {@code 1000}; reserved ranges: {@code [0..99]} for platform internals, {@code [100..999]}
 *       for infra plugins, {@code [1000..]} for user plugins.
 *   <li>{@link #apply(PluginContext)} <b>must</b> return a non-null {@link Mono}; returning {@code
 *       Mono.empty()} signals "done, continue". Errors propagate and abort the pipeline unless the
 *       caller explicitly recovers.
 * </ul>
 */
public interface GatewayPlugin {

  /** Unique, stable identifier for this plugin (e.g. {@code "x-request-id"}). */
  String name();

  /**
   * Ordering within the plugin's {@link #stage()}. Lower runs first. Defaults to {@code 1000} —
   * user-plugin territory.
   */
  default int order() {
    return 1000;
  }

  /** Pipeline stage this plugin targets. */
  PipelineStage stage();

  /**
   * Executes the plugin.
   *
   * @param context per-request context (non-null)
   * @return completion signal. {@code Mono.empty()} is fine for synchronous plugins; async work
   *     should return a {@code Mono} that completes when done.
   */
  Mono<Void> apply(PluginContext context);
}
