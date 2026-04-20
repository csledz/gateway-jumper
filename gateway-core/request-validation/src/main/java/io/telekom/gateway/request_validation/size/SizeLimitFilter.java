// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.size;

import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import io.telekom.gateway.request_validation.config.RequestValidationProperties;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Enforces a per-request body-size cap. Fast path: Content-Length header. Streaming path: decorates
 * the request with a running-sum guard that errors out as soon as the cumulative byte count exceeds
 * the cap; buffers are released on cancel to avoid Netty direct-memory leaks.
 */
@Slf4j
public class SizeLimitFilter implements WebFilter, Ordered {

  public static final int ORDER = 130;
  private final PolicyLookup lookup;
  private final RequestValidationProperties defaults;

  public SizeLimitFilter(PolicyLookup lookup, RequestValidationProperties defaults) {
    this.lookup = lookup;
    this.defaults = defaults;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    long cap = capFor(exchange);
    long declared = exchange.getRequest().getHeaders().getContentLength();
    if (declared > cap) {
      return reject(exchange, declared, cap);
    }
    ServerHttpRequestDecorator decorated = new ByteCountingRequest(exchange.getRequest(), cap);
    return chain
        .filter(exchange.mutate().request(decorated).build())
        .onErrorResume(
            PayloadTooLargeException.class,
            ex -> {
              log.warn("request body exceeded cap: observed={} cap={}", ex.observed, ex.cap);
              return reject(exchange, ex.observed, ex.cap);
            });
  }

  private long capFor(ServerWebExchange exchange) {
    return lookup
        .find(exchange)
        .map(ValidationPolicy::maxRequestBytes)
        .filter(v -> v > 0)
        .orElse(defaults.maxRequestBytes());
  }

  private Mono<Void> reject(ServerWebExchange exchange, long observed, long cap) {
    exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
    exchange
        .getResponse()
        .getHeaders()
        .add("X-Reject-Reason", "body exceeds " + cap + " bytes (observed " + observed + ")");
    return exchange.getResponse().setComplete();
  }

  private static final class ByteCountingRequest extends ServerHttpRequestDecorator {
    private final long cap;

    ByteCountingRequest(ServerHttpRequest delegate, long cap) {
      super(delegate);
      this.cap = cap;
    }

    @Override
    public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
      AtomicLong total = new AtomicLong();
      return super.getBody()
          .handle(
              (buf, sink) -> {
                long now = total.addAndGet(buf.readableByteCount());
                if (now > cap) {
                  DataBufferUtils.release(buf);
                  sink.error(new PayloadTooLargeException(now, cap));
                  return;
                }
                sink.next(buf);
              });
    }
  }

  private static final class PayloadTooLargeException extends RuntimeException {
    final long observed;
    final long cap;

    PayloadTooLargeException(long observed, long cap) {
      super("payload too large");
      this.observed = observed;
      this.cap = cap;
    }
  }
}
