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
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Trivial reactor-netty HTTP server that returns {@code 200 OK} with a tiny fixed body. Tuned to
 * never be the bottleneck of a load run:
 *
 * <ul>
 *   <li>Large accept queue ({@code SO_BACKLOG = 4096}) so connection establishment isn't gated.
 *   <li>{@code TCP_NODELAY} so tail latency is not dominated by Nagle.
 *   <li>Response body is a pre-built, shared {@link ByteBuf} slice (≤256B), no per-request
 *       allocation.
 *   <li>No request decoding / no logging per-request - the handler just writes the response.
 * </ul>
 *
 * <p>Exposes its own counters ({@link #getOpenConnections()}, {@link #getInFlight()}) so the driver
 * can cross-check that the upstream was never the thing that saturated.
 */
@Slf4j
public final class FastUpstream {

  private static final byte[] BODY_BYTES =
      "{\"ok\":true,\"src\":\"fast-upstream\"}".getBytes(StandardCharsets.UTF_8);

  /** Shared, retained response payload. All writes use a retained duplicate. */
  private static final ByteBuf RESPONSE =
      Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(BODY_BYTES));

  @Getter private final AtomicLong openConnections = new AtomicLong();
  @Getter private final AtomicLong inFlight = new AtomicLong();
  @Getter private final AtomicLong totalRequests = new AtomicLong();

  private volatile DisposableServer server;

  /** Starts the server on the given port (0 = pick free). Returns the actual port. */
  public int start(int port) {
    HttpServer http =
        HttpServer.create()
            .port(port)
            .option(ChannelOption.SO_BACKLOG, 4096)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            // Accept any body size; we don't read it. This is faster than the default decoder
            // buffering.
            .httpRequestDecoder(spec -> spec.maxInitialLineLength(8192).maxHeaderSize(16384))
            .doOnConnection(c -> openConnections.incrementAndGet())
            .doOnChannelInit(
                (obs, channel, addr) ->
                    channel.closeFuture().addListener(f -> openConnections.decrementAndGet()))
            .handle(
                (req, resp) -> {
                  inFlight.incrementAndGet();
                  totalRequests.incrementAndGet();
                  return resp.status(200)
                      .header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                      .header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(BODY_BYTES.length))
                      .sendObject(RESPONSE.retainedDuplicate())
                      .then()
                      .doFinally(s -> inFlight.decrementAndGet());
                });
    this.server = http.bindNow();
    log.info("FastUpstream bound on port {}", server.port());
    return server.port();
  }

  /** Returns the bound port. */
  public int port() {
    return server == null ? -1 : server.port();
  }

  /** Disposes the server and waits for it to release its resources. */
  public void stop() {
    if (server != null) {
      server.disposeNow();
      server = null;
    }
  }
}
