// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Reactor-netty HTTP server that responds with {@code 200 OK} after a configurable delay sampled
 * from a latency distribution. Identical hot path to {@link FastUpstream} (no body decoding, no
 * per-request logging, shared response buffer) - the only difference is the {@link
 * Mono#delay(Duration)} before the response is written.
 *
 * <p>The mean latency (and the distribution shape) can be swapped at runtime via {@link
 * #setLatency(LatencyDistribution, Duration)} - the slow-upstream scenario uses this to ramp mean
 * latency over the course of a run and observe the gateway's open-connection count grow in
 * response.
 */
@Slf4j
public final class SlowUpstream {

  private static final byte[] BODY_BYTES =
      "{\"ok\":true,\"src\":\"slow-upstream\"}".getBytes(StandardCharsets.UTF_8);

  private static final ByteBuf RESPONSE =
      Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(BODY_BYTES));

  @Getter private final AtomicLong openConnections = new AtomicLong();
  @Getter private final AtomicLong activeHandlers = new AtomicLong();
  @Getter private final AtomicLong totalRequests = new AtomicLong();

  private final AtomicReference<Setting> setting =
      new AtomicReference<>(new Setting(LatencyDistribution.CONSTANT, Duration.ofMillis(10)));

  private volatile DisposableServer server;

  /** Starts the server on the given port (0 = free). Returns the actual port. */
  public int start(int port) {
    HttpServer http =
        HttpServer.create()
            .port(port)
            .option(ChannelOption.SO_BACKLOG, 4096)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .httpRequestDecoder(spec -> spec.maxInitialLineLength(8192).maxHeaderSize(16384))
            .doOnConnection(c -> openConnections.incrementAndGet())
            .doOnChannelInit(
                (obs, channel, addr) ->
                    channel.closeFuture().addListener(f -> openConnections.decrementAndGet()))
            .handle(
                (req, resp) -> {
                  activeHandlers.incrementAndGet();
                  totalRequests.incrementAndGet();
                  Duration d = sampleDelay();
                  return Mono.delay(d)
                      .then(
                          resp.status(200)
                              .header(
                                  HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                              .header(
                                  HttpHeaderNames.CONTENT_LENGTH, String.valueOf(BODY_BYTES.length))
                              .sendObject(RESPONSE.retainedDuplicate())
                              .then())
                      .doFinally(s -> activeHandlers.decrementAndGet());
                });
    this.server = http.bindNow();
    log.info("SlowUpstream bound on port {}", server.port());
    return server.port();
  }

  /** Updates the latency distribution in place. Safe to call while the server is serving. */
  public void setLatency(LatencyDistribution kind, Duration mean) {
    setting.set(new Setting(kind, mean));
  }

  /** Current mean-latency setting. */
  public Duration currentMean() {
    return setting.get().mean();
  }

  public int port() {
    return server == null ? -1 : server.port();
  }

  public void stop() {
    if (server != null) {
      server.disposeNow();
      server = null;
    }
  }

  private Duration sampleDelay() {
    Setting s = setting.get();
    long meanNanos = s.mean().toNanos();
    long nanos =
        switch (s.kind()) {
          case CONSTANT -> meanNanos;
          case NORMAL -> {
            // Approximately normal via sum-of-uniforms; stddev = mean/4, clamped to [1, 4*mean].
            double u = ThreadLocalRandom.current().nextGaussian() * (meanNanos / 4.0) + meanNanos;
            yield Math.max(1L, Math.min((long) u, 4L * meanNanos));
          }
          case PARETO -> {
            // Pareto with alpha=1.16 (classic heavy-tail). mean = xm * alpha / (alpha - 1).
            double alpha = 1.16;
            double xm = meanNanos * (alpha - 1) / alpha;
            double u = ThreadLocalRandom.current().nextDouble();
            double v = xm / Math.pow(u, 1.0 / alpha);
            yield Math.max(1L, Math.min((long) v, 10L * meanNanos));
          }
        };
    return Duration.ofNanos(nanos);
  }

  /** Shape of the delay distribution. */
  public enum LatencyDistribution {
    CONSTANT,
    NORMAL,
    PARETO
  }

  private record Setting(LatencyDistribution kind, Duration mean) {}
}
