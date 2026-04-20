// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.telekom.gateway.observability.ObservabilityConstants;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter recording RED (Rate / Errors / Duration) metrics per route.
 *
 * <p>Runs at a very low order so it wraps upstream work and sees the final status code when the
 * response completes.
 */
@Slf4j
public class RedMetricsFilter implements GlobalFilter, Ordered {

  private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  private final MeterRegistry meterRegistry;
  private final String zone;

  public RedMetricsFilter(
      MeterRegistry meterRegistry,
      @Value("${gateway.observability.zone:${gateway.zone.name:default}}") String zone) {
    this.meterRegistry = meterRegistry;
    this.zone = zone;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    long startNanos = System.nanoTime();
    return chain
        .filter(exchange)
        .doFinally(signal -> record(exchange, System.nanoTime() - startNanos));
  }

  private void record(ServerWebExchange exchange, long elapsedNanos) {
    String route = resolveRoute(exchange);
    String method =
        exchange.getRequest().getMethod() == null
            ? "UNKNOWN"
            : exchange.getRequest().getMethod().name();
    String status = resolveStatus(exchange);

    Tags tags =
        Tags.of(
            ObservabilityConstants.TAG_ROUTE, route,
            ObservabilityConstants.TAG_METHOD, method,
            ObservabilityConstants.TAG_STATUS, status,
            ObservabilityConstants.TAG_ZONE, zone);

    meterRegistry.counter(ObservabilityConstants.METRIC_REQUESTS, tags).increment();

    Timer.builder(ObservabilityConstants.METRIC_REQUEST_DURATION)
        .description("End-to-end request duration for gateway routes")
        .tags(tags)
        .publishPercentileHistogram()
        .register(meterRegistry)
        .record(Duration.ofNanos(elapsedNanos));

    if (log.isDebugEnabled()) {
      log.debug(
          "RED metric recorded route={} method={} status={} zone={} elapsedMs={}",
          route,
          method,
          status,
          zone,
          Duration.ofNanos(elapsedNanos).toMillis());
    }
  }

  private static String resolveRoute(ServerWebExchange exchange) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route != null) {
      return route.getId();
    }
    String path = exchange.getRequest().getPath().value();
    return path.isEmpty() ? "unknown" : path;
  }

  private static String resolveStatus(ServerWebExchange exchange) {
    var status = exchange.getResponse().getStatusCode();
    return status == null ? "0" : Integer.toString(status.value());
  }

  @Override
  public int getOrder() {
    return FILTER_ORDER;
  }
}
