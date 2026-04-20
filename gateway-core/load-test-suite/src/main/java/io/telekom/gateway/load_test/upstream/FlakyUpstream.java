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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Reactor-netty HTTP server that returns a 5xx on a configurable fraction of requests and a 200 OK
 * on the rest. Used by the resilience-under-load scenario to verify the gateway's
 * retry/circuit-breaker does not amplify upstream badness into a self-inflicted meltdown.
 */
@Slf4j
public final class FlakyUpstream {

  private static final byte[] OK_BODY =
      "{\"ok\":true,\"src\":\"flaky-upstream\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FAIL_BODY =
      "{\"ok\":false,\"src\":\"flaky-upstream\"}".getBytes(StandardCharsets.UTF_8);

  private static final ByteBuf OK_BUF =
      Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(OK_BODY));
  private static final ByteBuf FAIL_BUF =
      Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(FAIL_BODY));

  /** Failure probability in basis-points (0..10000). Atomic so it can be nudged mid-run. */
  private final AtomicInteger failBasisPoints = new AtomicInteger(2000); // 20% by default

  @Getter private final AtomicLong total = new AtomicLong();
  @Getter private final AtomicLong failures = new AtomicLong();

  private volatile DisposableServer server;

  /** Starts the server. Returns the bound port. */
  public int start(int port) {
    HttpServer http =
        HttpServer.create()
            .port(port)
            .option(ChannelOption.SO_BACKLOG, 4096)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .httpRequestDecoder(spec -> spec.maxInitialLineLength(8192).maxHeaderSize(16384))
            .handle(
                (req, resp) -> {
                  total.incrementAndGet();
                  int bp = failBasisPoints.get();
                  boolean fail = ThreadLocalRandom.current().nextInt(10_000) < bp;
                  if (fail) {
                    failures.incrementAndGet();
                    return resp.status(503)
                        .header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                        .header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(FAIL_BODY.length))
                        .sendObject(FAIL_BUF.retainedDuplicate())
                        .then();
                  }
                  return resp.status(200)
                      .header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                      .header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(OK_BODY.length))
                      .sendObject(OK_BUF.retainedDuplicate())
                      .then();
                });
    this.server = http.bindNow();
    log.info(
        "FlakyUpstream bound on port {}, failure rate = {} bp",
        server.port(),
        failBasisPoints.get());
    return server.port();
  }

  /**
   * Sets the failure probability. Caller passes a fraction in {@code [0.0, 1.0]}; value is stored
   * as basis points for lock-free updates.
   */
  public void setFailureRate(double fraction) {
    int bp = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * 10_000);
    failBasisPoints.set(bp);
  }

  public double currentFailureRate() {
    return failBasisPoints.get() / 10_000.0;
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
}
