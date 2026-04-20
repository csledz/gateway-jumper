// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.driver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Open-loop load driver.
 *
 * <p>Emits requests at a target rate (with optional ramp) against a target URL, records per-request
 * wall-clock latency into an {@link Histogram HdrHistogram}, and classifies the response by
 * 2xx/4xx/5xx buckets. Concurrency is bounded by a {@link Semaphore} acquired before each emission
 * so the driver never runs away if the target stalls.
 *
 * <p>Non-goals:
 *
 * <ul>
 *   <li>No per-request logging (would dominate JIT / allocations at high RPS).
 *   <li>No ad-hoc heap allocations for the request body (fixed {@link ByteBuf} re-sent per
 *       request).
 *   <li>No response-body decoding unless the caller explicitly asks for it; {@code discard()}
 *       drains the socket.
 * </ul>
 */
@Slf4j
public final class LoadDriver {

  private final String targetUrl;
  private final HttpClient http;
  private final ConnectionProvider pool;

  // Buckets: 100us -> 60s, 3 sig digits. Covers the latency range we actually care about for a
  // gateway load test without wasting memory on 6+ sigfigs.
  private final Histogram latency = new ConcurrentHistogram(TimeUnit.SECONDS.toMicros(60), 3);

  private final AtomicLong status2xx = new AtomicLong();
  private final AtomicLong status4xx = new AtomicLong();
  private final AtomicLong status5xx = new AtomicLong();
  private final AtomicLong errorCount = new AtomicLong();
  private final AtomicLong totalRequests = new AtomicLong();

  public LoadDriver(String targetUrl) {
    this.targetUrl = targetUrl;
    this.pool =
        ConnectionProvider.builder("load-driver")
            .maxConnections(4096)
            .pendingAcquireMaxCount(
                -1) // no queue limit; we want to stress upstream's pool, not ours
            .pendingAcquireTimeout(Duration.ofSeconds(30))
            .build();
    this.http =
        HttpClient.create(pool)
            .compress(false)
            .keepAlive(true)
            .responseTimeout(Duration.ofSeconds(30));
  }

  /**
   * Runs {@code profile} and returns the collated report. Blocks the caller until the run is
   * complete.
   *
   * <p>Scheduling runs on a dedicated {@link ScheduledExecutorService} rather than on a reactive
   * interval - {@link reactor.core.publisher.Flux#interval(Duration)} surfaces an {@code
   * OverflowException} the moment per-tick emission work outpaces the inner subscription, which we
   * explicitly want to tolerate (that *is* the overload case we're measuring).
   */
  public LoadReport run(LoadProfile profile) {
    resetCounters();
    Semaphore inflight = new Semaphore(Math.max(1, profile.concurrency()));
    ByteBuf body =
        profile.bodyBytes() > 0
            ? Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(filler(profile.bodyBytes())))
            : null;

    IntUnaryOperator rpsForSecond = scheduleRps(profile);
    long runMillis = profile.durationSeconds() * 1000L + profile.rampSeconds() * 1000L;
    long startNanos = System.nanoTime();
    long endNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(runMillis);
    int maxSecondIndex = profile.durationSeconds() + profile.rampSeconds() - 1;

    ScheduledExecutorService tickExec =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "load-driver-ticker");
              t.setDaemon(true);
              return t;
            });
    CountDownLatch done = new CountDownLatch(1);

    ScheduledFuture<?> scheduled =
        tickExec.scheduleAtFixedRate(
            () -> {
              long now = System.nanoTime();
              if (now >= endNanos) {
                done.countDown();
                return;
              }
              int secondIndex =
                  (int) Math.min(maxSecondIndex, TimeUnit.NANOSECONDS.toSeconds(now - startNanos));
              int toEmit = Math.max(0, rpsForSecond.applyAsInt(secondIndex)) / 100;
              for (int i = 0; i < toEmit; i++) {
                emitOne(inflight, profile.headers(), body).subscribe();
              }
            },
            0,
            10,
            TimeUnit.MILLISECONDS);

    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      scheduled.cancel(false);
      tickExec.shutdown();
    }

    // Wait long enough for stragglers to complete (pool-acquire + response timeouts) so their
    // latencies land in this run's histogram, not the next one.
    drainInflight(inflight, profile.concurrency(), Duration.ofSeconds(35));

    return report();
  }

  private static void drainInflight(Semaphore inflight, int concurrency, Duration deadline) {
    long ddl = System.nanoTime() + deadline.toNanos();
    int acquired = 0;
    try {
      while (acquired < concurrency) {
        long remaining = ddl - System.nanoTime();
        if (remaining <= 0) break;
        if (inflight.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
          acquired++;
        } else {
          break;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Mono<Void> emitOne(Semaphore inflight, Map<String, String> headers, ByteBuf body) {
    if (!inflight.tryAcquire()) {
      // No capacity - drop this emission. This models a client that can't exceed its concurrency
      // cap; it becomes a queueing signal visible in totalRequests vs. targetRps.
      return Mono.empty();
    }
    long t0 = System.nanoTime();
    totalRequests.incrementAndGet();
    HttpClient prepared = http;
    if (headers != null && !headers.isEmpty()) {
      prepared = prepared.headers(h -> headers.forEach(h::set));
    }
    HttpClient.ResponseReceiver<?> receiver;
    if (body != null) {
      receiver =
          prepared
              .post()
              .uri(targetUrl)
              .send((req, out) -> out.send(Mono.just(body.retainedDuplicate())));
    } else {
      receiver = prepared.get().uri(targetUrl);
    }
    // Use .responseSingle() so the body is aggregated/drained in one shot: without this the
    // connection is never returned to the pool (the body is left dangling on the wire),
    // straggling requests hit the 30s pool-acquire timeout, and p99 is dominated by that
    // artefact of the harness rather than by real gateway latency.
    return receiver
        .responseSingle(
            (r, bodyMono) ->
                bodyMono
                    .defaultIfEmpty(io.netty.buffer.Unpooled.EMPTY_BUFFER)
                    .doOnNext(buf -> buf.release())
                    .then(Mono.fromCallable(() -> r.status().code())))
        .doOnNext(
            code -> {
              if (code >= 200 && code < 300) status2xx.incrementAndGet();
              else if (code >= 400 && code < 500) status4xx.incrementAndGet();
              else if (code >= 500) status5xx.incrementAndGet();
              recordLatency(t0);
            })
        .doOnError(
            e -> {
              errorCount.incrementAndGet();
              recordLatency(t0);
            })
        .onErrorResume(e -> Mono.empty())
        .doFinally(s -> inflight.release())
        .then()
        .subscribeOn(Schedulers.parallel());
  }

  private void recordLatency(long t0) {
    long micros = (System.nanoTime() - t0) / 1000;
    if (micros < 1) micros = 1;
    long max = latency.getHighestTrackableValue();
    if (micros > max) micros = max;
    latency.recordValue(micros);
  }

  private LoadReport report() {
    return new LoadReport(
        toMs(latency.getValueAtPercentile(50)),
        toMs(latency.getValueAtPercentile(95)),
        toMs(latency.getValueAtPercentile(99)),
        toMs(latency.getValueAtPercentile(99.9)),
        toMs(latency.getMaxValue()),
        errorCount.get(),
        status2xx.get(),
        status4xx.get(),
        status5xx.get(),
        totalRequests.get());
  }

  private static double toMs(long micros) {
    return micros / 1000.0;
  }

  private void resetCounters() {
    latency.reset();
    status2xx.set(0);
    status4xx.set(0);
    status5xx.set(0);
    errorCount.set(0);
    totalRequests.set(0);
  }

  private static IntUnaryOperator scheduleRps(LoadProfile p) {
    if (p.rampSeconds() <= 0) {
      return i -> p.targetRps();
    }
    return i -> {
      if (i < p.rampSeconds()) {
        return (int) ((long) p.targetRps() * (i + 1) / p.rampSeconds());
      }
      return p.targetRps();
    };
  }

  private static byte[] filler(int n) {
    byte[] b = new byte[n];
    byte[] seed = "load-test".getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < n; i++) b[i] = seed[i % seed.length];
    return b;
  }

  /**
   * Shuts down the underlying connection pool. Callers must invoke after all runs finish, or leak
   * sockets.
   */
  public void close() {
    pool.disposeLater().block(Duration.ofSeconds(5));
  }
}
