/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Context handed to a {@link GatewayPlugin} at each pipeline stage.
 *
 * <p>This record wraps the underlying server exchange without exposing Spring types to plugin
 * authors — they only ever see this API. The {@link #exchange()} handle is typed as {@code Object}
 * on purpose: the runtime passes the live {@code ServerWebExchange}, but plugin authors should
 * interact through the header / attribute accessors defined here.
 *
 * @param exchange opaque handle to the server exchange (runtime-specific)
 * @param stage the stage at which this invocation is happening
 * @param attributes mutable attribute bag shared across all plugins in this request
 * @param requestHeaders read/write view of the request headers
 * @param responseHeaders read/write view of the response headers (may be empty before upstream)
 */
public record PluginContext(
    Object exchange,
    PipelineStage stage,
    Map<String, Object> attributes,
    HeaderAccessor requestHeaders,
    HeaderAccessor responseHeaders) {

  public PluginContext {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(attributes, "attributes");
    Objects.requireNonNull(requestHeaders, "requestHeaders");
    Objects.requireNonNull(responseHeaders, "responseHeaders");
  }

  /** Convenience: get an attribute cast to the requested type, or empty if absent / mismatched. */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> attribute(String key, Class<T> type) {
    Object v = attributes.get(key);
    if (v == null || !type.isInstance(v)) {
      return Optional.empty();
    }
    return Optional.of((T) v);
  }

  /** Store an attribute value, returning this context for fluent chaining. */
  public PluginContext putAttribute(String key, Object value) {
    attributes.put(key, value);
    return this;
  }

  /**
   * Minimal header accessor so plugin authors never import {@code HttpHeaders} or other Spring
   * types. Implementations are provided by the runtime.
   */
  public interface HeaderAccessor {

    /** First value for the header, case-insensitive, or {@code null} if absent. */
    String getFirst(String name);

    /** All values for the header, case-insensitive; empty list if absent. */
    java.util.List<String> getAll(String name);

    /** Set a single value (replacing any existing values). */
    void set(String name, String value);

    /** Append a value (keeping any existing values). */
    void add(String name, String value);

    /** Remove all values for the given header. */
    void remove(String name);

    /** True if the header is present with at least one value. */
    boolean contains(String name);
  }
}
