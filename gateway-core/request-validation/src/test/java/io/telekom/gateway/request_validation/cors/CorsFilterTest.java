// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.cors;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class CorsFilterTest {

  private final CorsPolicy policy =
      new CorsPolicy(
          List.of("https://app.example.com"),
          List.of("GET", "POST"),
          List.of("Content-Type", "Authorization"),
          List.of("X-Request-Id"),
          3600,
          false);
  private final PolicyLookup lookup =
      ex -> Optional.of(new ValidationPolicy(policy, null, null, 0));
  private final CorsFilter filter = new CorsFilter(lookup);
  private final WebFilterChain passthrough =
      ex -> {
        ex.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
      };

  @Test
  void preflightAllowed() {
    MockServerHttpRequest request =
        MockServerHttpRequest.method(HttpMethod.OPTIONS, "/resource")
            .header(HttpHeaders.ORIGIN, "https://app.example.com")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    filter
        .filter(
            exchange,
            ex -> {
              throw new AssertionError("preflight must not hit the chain");
            })
        .block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    HttpHeaders out = exchange.getResponse().getHeaders();
    assertThat(out.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("https://app.example.com");
    assertThat(out.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("POST");
  }

  @Test
  void preflightDeniedForForeignOrigin() {
    MockServerHttpRequest request =
        MockServerHttpRequest.method(HttpMethod.OPTIONS, "/resource")
            .header(HttpHeaders.ORIGIN, "https://evil.example.com")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void actualRequestGetsAllowOriginOnResponseCommit() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/resource")
            .header(HttpHeaders.ORIGIN, "https://app.example.com")
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    filter.filter(exchange, passthrough).block();
    // Force response commit so the beforeCommit hook runs.
    exchange.getResponse().setComplete().block();

    assertThat(
            exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("https://app.example.com");
  }
}
