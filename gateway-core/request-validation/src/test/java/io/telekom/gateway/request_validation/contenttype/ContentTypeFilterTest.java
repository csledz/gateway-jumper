// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.contenttype;

import static org.assertj.core.api.Assertions.assertThat;

import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import io.telekom.gateway.request_validation.config.RequestValidationProperties;
import io.telekom.gateway.request_validation.cors.CorsPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ContentTypeFilterTest {

  private final RequestValidationProperties defaults =
      new RequestValidationProperties(0, List.of("application/json"));
  private final CorsPolicy cors =
      new CorsPolicy(List.of(), List.of(), List.of(), List.of(), 0, false);

  @Test
  void allowsMatchingContentType() {
    PolicyLookup lookup =
        ex -> Optional.of(new ValidationPolicy(cors, null, List.of("application/json"), 0));
    ContentTypeFilter filter = new ContentTypeFilter(lookup, defaults);
    MockServerWebExchange exchange = withJsonBody("{\"k\":1}");

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode())
        .isNotEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void rejectsForeignContentType() {
    PolicyLookup lookup =
        ex -> Optional.of(new ValidationPolicy(cors, null, List.of("application/json"), 0));
    ContentTypeFilter filter = new ContentTypeFilter(lookup, defaults);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/r")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(2)
                .body("hi"));

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void fallsBackToDefaultsWhenPolicyMissing() {
    PolicyLookup lookup = ex -> Optional.empty();
    ContentTypeFilter filter = new ContentTypeFilter(lookup, defaults);
    MockServerWebExchange exchange = withJsonBody("{}");

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode())
        .isNotEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  private MockServerWebExchange withJsonBody(String body) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.post("/r")
            .contentType(MediaType.APPLICATION_JSON)
            .contentLength(body.length())
            .body(body));
  }
}
