// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.driver;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * Polls the gateway's {@code /actuator/prometheus} endpoint on a fixed cadence while a load run is
 * in progress and keeps a time-ordered list of samples for a curated set of metric families.
 *
 * <p>Parsed metrics (matching by name prefix, summing across tags):
 *
 * <ul>
 *   <li>{@code reactor_netty_connection_provider_active_connections} - live outbound connections
 *       (driver-gateway-upstream hops)
 *   <li>{@code reactor_netty_connection_provider_pending_acquire} - requests waiting for a
 *       connection from the pool (pool-saturation signal)
 *   <li>{@code reactor_netty_connection_provider_max_connections} - static pool cap
 *   <li>{@code jvm_memory_used_bytes} - memory pressure
 *   <li>{@code http_server_requests_seconds_count} - gateway-observed request count
 *   <li>{@code reactor_netty_eventloop_pending_tasks} - event-loop lag signal (emitted when
 *       available)
 * </ul>
 *
 * <p>Uses a tiny line-oriented parser rather than pulling in a full Prometheus client - the load
 * suite has no business decoding histograms here.
 */
@Slf4j
public final class GatewayMetricsSampler {

  private static final Pattern METRIC_LINE =
      Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*\\})?\\s+([\\-0-9eE.+NaInf]+).*$");

  private final HttpClient http;
  private final String prometheusUrl;
  private final Duration period;

  private final AtomicBoolean running = new AtomicBoolean();
  private Disposable subscription;

  // Per-metric time-series of samples (summed across tags).
  private final Map<String, CopyOnWriteArrayList<Double>> series = new ConcurrentHashMap<>();

  public GatewayMetricsSampler(String prometheusUrl) {
    this(prometheusUrl, Duration.ofMillis(500));
  }

  public GatewayMetricsSampler(String prometheusUrl, Duration period) {
    this.prometheusUrl = prometheusUrl;
    this.period = period;
    this.http = HttpClient.create().responseTimeout(Duration.ofSeconds(2));
  }

  /** Starts sampling. Idempotent; safe to call multiple times. */
  public void start() {
    if (!running.compareAndSet(false, true)) return;
    subscription =
        Flux.interval(Duration.ZERO, period)
            .onBackpressureDrop()
            .concatMap(t -> fetchOnce())
            .subscribe(
                body -> parseAndStore(body),
                err -> log.debug("metrics sample error: {}", err.toString()));
  }

  /** Stops sampling. Idempotent. */
  public void stop() {
    if (!running.compareAndSet(true, false)) return;
    if (subscription != null) subscription.dispose();
  }

  /** Returns the time-ordered series for the given metric name (empty if never observed). */
  public List<Double> samplesFor(String metric) {
    CopyOnWriteArrayList<Double> s = series.get(metric);
    return s == null ? List.of() : List.copyOf(s);
  }

  /** Convenience: active outbound connections. */
  public List<Long> activeConnections() {
    return samplesFor("reactor_netty_connection_provider_active_connections").stream()
        .map(d -> (long) Math.round(d))
        .toList();
  }

  /** Convenience: pending acquires (pool saturation). Non-empty only if pool saturates. */
  public List<Double> pendingAcquireMs() {
    // Actuator exposes pending-count and acquire-time separately; we surface the count here, which
    // is the direct saturation signal. Naming keeps parity with the report field.
    return samplesFor("reactor_netty_connection_provider_pending_acquire");
  }

  /** Convenience: max pool size last observed (0 if not reported). */
  public long maxConnections() {
    List<Double> v = samplesFor("reactor_netty_connection_provider_max_connections");
    return v.isEmpty() ? 0 : (long) Math.round(v.get(v.size() - 1));
  }

  private reactor.core.publisher.Mono<String> fetchOnce() {
    return http.get()
        .uri(prometheusUrl)
        .responseContent()
        .aggregate()
        .asString()
        .onErrorResume(
            e -> {
              log.debug("metrics fetch failed: {}", e.toString());
              return reactor.core.publisher.Mono.empty();
            });
  }

  private void parseAndStore(String body) {
    if (body == null || body.isEmpty()) return;
    // Sum values across tagged time-series per metric-name for the interesting families.
    Map<String, Double> tick = new java.util.HashMap<>();
    for (String line : body.split("\n")) {
      if (line.isEmpty() || line.charAt(0) == '#') continue;
      Matcher m = METRIC_LINE.matcher(line);
      if (!m.matches()) continue;
      String name = m.group(1);
      if (!isInteresting(name)) continue;
      double v;
      try {
        v = Double.parseDouble(m.group(3));
      } catch (NumberFormatException nfe) {
        continue;
      }
      tick.merge(name, v, Double::sum);
    }
    for (Map.Entry<String, Double> e : tick.entrySet()) {
      series.computeIfAbsent(e.getKey(), k -> new CopyOnWriteArrayList<>()).add(e.getValue());
    }
  }

  private static boolean isInteresting(String name) {
    return name.startsWith("reactor_netty_connection_provider_")
        || name.startsWith("reactor_netty_eventloop_")
        || name.equals("jvm_memory_used_bytes")
        || name.startsWith("http_server_requests_seconds");
  }
}
