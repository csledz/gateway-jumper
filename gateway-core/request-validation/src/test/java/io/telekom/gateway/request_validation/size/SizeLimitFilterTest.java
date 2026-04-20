// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.size;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.config.RequestValidationProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class SizeLimitFilterTest {

  private final RequestValidationProperties defaults =
      new RequestValidationProperties(16, List.of("application/json"));
  private final PolicyLookup lookup = ex -> Optional.empty();
  private final SizeLimitFilter filter = new SizeLimitFilter(lookup, defaults);

  @Test
  void rejectsByDeclaredContentLength() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/r")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(32)
                .body("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"));

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void allowsWithinCap() {
    String body = "small";
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/r")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length())
                .body(body));

    filter
        .filter(
            exchange,
            ex ->
                DataBufferUtils.join(ex.getRequest().getBody())
                    .doOnNext(DataBufferUtils::release)
                    .then())
        .block();

    assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }
}
