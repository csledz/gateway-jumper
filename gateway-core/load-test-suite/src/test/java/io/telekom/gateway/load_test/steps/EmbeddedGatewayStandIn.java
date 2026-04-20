// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.load_test.steps;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

/**
 * Minimal stand-in for the real gateway-core proxy. Does not implement auth, routing rules,
 * rate-limiting, circuit breakers, or any other feature that the sibling {@code gateway-core/proxy}
 * module owns. Its only job is to:
 *
 * <ul>
 *   <li>Accept an inbound HTTP request.
 *   <li>Proxy it to a single configured upstream origin via a bounded reactor-netty pool.
 *   <li>Expose Prometheus-format metrics on {@code /actuator/prometheus} that reproduce the metric
 *       names the real gateway emits: {@code reactor_netty_connection_provider_active_connections}
 *       (tracked here as proxy requests in-flight - the thing that directly scales with upstream
 *       latency at fixed arrival rate), {@code ..._pending_acquire} (pool queue depth), and {@code
 *       ..._max_connections} (pool cap).
 * </ul>
 *
 * <p>When the sibling gateway-core/proxy module lands, replace the bean wiring below with a real
 * {@code GatewayApplication} without touching the scenarios or Cucumber steps.
 */
@Slf4j
public final class EmbeddedGatewayStandIn {

  private final int maxConnections;
  private final String upstreamBaseUrl;

  private final PrometheusMeterRegistry registry =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  // activeConnections = proxy requests currently in-flight. At fixed arrival rate this is
  // arrival_rate * upstream_latency (Little's Law) until the outbound pool saturates, after
  // which the excess shows up in pendingAcquire.
  private final AtomicLong activeConnections = new AtomicLong();
  private final AtomicLong pendingAcquire = new AtomicLong();
  private final AtomicLong maxConns = new AtomicLong();
  private final Counter requestsCounter;

  private ConnectionProvider pool;
  private DisposableServer server;
  @Getter private int port;

  public EmbeddedGatewayStandIn(int maxConnections, String upstreamBaseUrl) {
    this.maxConnections = maxConnections;
    this.upstreamBaseUrl = upstreamBaseUrl;
    this.maxConns.set(maxConnections);

    // Gauges intentionally reproduce the metric names the real gateway emits so the sampler is
    // agnostic to which one is behind the endpoint.
    Gauge.builder(
            "reactor.netty.connection.provider.active.connections", activeConnections::doubleValue)
        .tag("name", "gateway-standin")
        .register(registry);
    Gauge.builder("reactor.netty.connection.provider.pending.acquire", pendingAcquire::doubleValue)
        .tag("name", "gateway-standin")
        .register(registry);
    Gauge.builder("reactor.netty.connection.provider.max.connections", maxConns::doubleValue)
        .tag("name", "gateway-standin")
        .register(registry);
    this.requestsCounter =
        Counter.builder("http.server.requests.seconds.count")
            .tag("uri", "/proxy")
            .register(registry);
  }

  /** Binds the proxy on an ephemeral port and returns it. */
  public int start() {
    this.pool =
        ConnectionProvider.builder("gateway-standin")
            .maxConnections(maxConnections)
            .pendingAcquireMaxCount(maxConnections * 4)
            .pendingAcquireTimeout(Duration.ofSeconds(5))
            .build();

    HttpClient upstream =
        HttpClient.create(pool)
            .compress(false)
            .keepAlive(true)
            .responseTimeout(Duration.ofSeconds(30));

    HttpServer http =
        HttpServer.create()
            .port(0)
            .option(ChannelOption.SO_BACKLOG, 4096)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .route(
                routes ->
                    routes
                        .get(
                            "/actuator/prometheus",
                            (req, resp) -> resp.sendString(Mono.fromSupplier(registry::scrape)))
                        .route(
                            req -> true,
                            (req, resp) -> {
                              requestsCounter.increment();
                              URI target = URI.create(upstreamBaseUrl + req.uri());
                              HttpMethod method = HttpMethod.valueOf(req.method().name());
                              HttpClient.ResponseReceiver<?> receiver =
                                  upstream
                                      .headers(h -> h.set("X-Forwarded-By", "gateway-standin"))
                                      .request(method)
                                      .uri(target);
                              // Outbound pool wrapper: increment active-connections gauge when the
                              // proxy actually starts processing the request, decrement when the
                              // response is fully sent or an error terminates the chain. Semantics
                              // match "requests in flight at the gateway" - which at fixed arrival
                              // rate scales linearly with upstream latency (Little's Law).
                              return receiver
                                  .responseSingle(
                                      (upstreamResp, bodyMono) ->
                                          bodyMono
                                              .asByteArray()
                                              .defaultIfEmpty(new byte[0])
                                              .flatMap(
                                                  bytes ->
                                                      resp.status(upstreamResp.status().code())
                                                          .sendByteArray(Mono.just(bytes))
                                                          .then()))
                                  .onErrorResume(
                                      e -> {
                                        log.debug("proxy error: {}", e.toString());
                                        return resp.status(502).send().then();
                                      })
                                  .doOnSubscribe(
                                      s -> {
                                        long current = activeConnections.incrementAndGet();
                                        if (current > maxConnections) {
                                          pendingAcquire.incrementAndGet();
                                        }
                                      })
                                  .doFinally(
                                      s -> {
                                        long before = activeConnections.getAndDecrement();
                                        if (before > maxConnections) {
                                          pendingAcquire.decrementAndGet();
                                        }
                                      });
                            }));

    this.server = http.bindNow();
    this.port = server.port();
    log.info("EmbeddedGatewayStandIn bound on port {} -> upstream {}", port, upstreamBaseUrl);
    return port;
  }

  public String prometheusUrl() {
    return "http://127.0.0.1:" + port + "/actuator/prometheus";
  }

  public String publicBaseUrl() {
    return "http://127.0.0.1:" + port;
  }

  public void stop() {
    if (server != null) {
      server.disposeNow();
      server = null;
    }
    if (pool != null) {
      pool.disposeLater().block(Duration.ofSeconds(2));
      pool = null;
    }
  }
}
