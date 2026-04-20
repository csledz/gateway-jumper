/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.filter;

import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import io.telekom.gateway.plugin_spi.api.PipelineStage;
import io.telekom.gateway.plugin_spi.api.PluginContext;
import io.telekom.gateway.plugin_spi.loader.PluginRegistry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runtime adapter that plugs the SPI into WebFlux.
 *
 * <p>For each incoming request it runs plugins at {@link PipelineStage#PRE_ROUTING} and {@link
 * PipelineStage#PRE_UPSTREAM} before delegating, then {@link PipelineStage#POST_UPSTREAM} and
 * {@link PipelineStage#POST_RESPONSE} after the chain completes.
 */
@Slf4j
@RequiredArgsConstructor
public class PluginDispatchFilter implements WebFilter, Ordered {

  public static final String ATTR_KEY = "io.telekom.gateway.plugin_spi.attributes";
  private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

  private final PluginRegistry registry;

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ConcurrentHashMap<String, Object> attrs = new ConcurrentHashMap<>();
    exchange.getAttributes().put(ATTR_KEY, attrs);

    PluginContext preRouting = contextFor(exchange, attrs, PipelineStage.PRE_ROUTING);
    PluginContext preUpstream = contextFor(exchange, attrs, PipelineStage.PRE_UPSTREAM);
    PluginContext postUpstream = contextFor(exchange, attrs, PipelineStage.POST_UPSTREAM);
    PluginContext postResponse = contextFor(exchange, attrs, PipelineStage.POST_RESPONSE);

    return runStage(PipelineStage.PRE_ROUTING, preRouting)
        .then(runStage(PipelineStage.PRE_UPSTREAM, preUpstream))
        .then(chain.filter(exchange))
        .then(runStage(PipelineStage.POST_UPSTREAM, postUpstream))
        .then(runStage(PipelineStage.POST_RESPONSE, postResponse));
  }

  private Mono<Void> runStage(PipelineStage stage, PluginContext ctx) {
    List<GatewayPlugin> plugins = registry.byStage(stage);
    if (plugins.isEmpty()) {
      return Mono.empty();
    }
    Mono<Void> acc = Mono.empty();
    for (GatewayPlugin p : plugins) {
      acc =
          acc.then(
              Mono.defer(() -> p.apply(ctx))
                  .doOnError(t -> log.error("Plugin '{}' failed at stage {}", p.name(), stage, t)));
    }
    return acc;
  }

  private PluginContext contextFor(
      ServerWebExchange exchange, ConcurrentHashMap<String, Object> attrs, PipelineStage stage) {
    return new PluginContext(
        exchange,
        stage,
        attrs,
        new WebFluxHeaderAccessor(exchange.getRequest().getHeaders()),
        new WebFluxHeaderAccessor(exchange.getResponse().getHeaders()));
  }

  /** Adapter that hides Spring's {@link HttpHeaders} from plugin authors. */
  static final class WebFluxHeaderAccessor implements PluginContext.HeaderAccessor {
    private final HttpHeaders headers;

    WebFluxHeaderAccessor(HttpHeaders headers) {
      this.headers = headers;
    }

    @Override
    public String getFirst(String name) {
      return headers.getFirst(name);
    }

    @Override
    public List<String> getAll(String name) {
      List<String> v = headers.get(name);
      return v == null ? List.of() : List.copyOf(v);
    }

    @Override
    public void set(String name, String value) {
      headers.set(name, value);
    }

    @Override
    public void add(String name, String value) {
      headers.add(name, value);
    }

    @Override
    public void remove(String name) {
      headers.remove(name);
    }

    @Override
    public boolean contains(String name) {
      return headers.containsKey(name);
    }
  }
}
