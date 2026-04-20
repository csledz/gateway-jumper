// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import io.telekom.gateway.request_validation.cors.CorsPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class JsonSchemaFilterTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchemaRegistry registry = new JsonSchemaRegistry(mapper);
  private final CorsPolicy cors =
      new CorsPolicy(List.of(), List.of(), List.of(), List.of(), 0, false);

  @Test
  void passesValidBody() {
    PolicyLookup lookup =
        ex -> Optional.of(new ValidationPolicy(cors, "classpath:/schemas/widget.json", null, 0));
    JsonSchemaFilter filter = new JsonSchemaFilter(lookup, registry, mapper);
    MockServerWebExchange exchange = jsonRequest("{\"name\":\"ok\",\"count\":5}");

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsTypeMismatchWithProblemJson() {
    PolicyLookup lookup =
        ex -> Optional.of(new ValidationPolicy(cors, "classpath:/schemas/widget.json", null, 0));
    JsonSchemaFilter filter = new JsonSchemaFilter(lookup, registry, mapper);
    MockServerWebExchange exchange = jsonRequest("{\"name\":\"ok\",\"count\":\"many\"}");

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exchange.getResponse().getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void passesWhenNoSchemaRef() {
    PolicyLookup lookup = ex -> Optional.of(new ValidationPolicy(cors, null, null, 0));
    JsonSchemaFilter filter = new JsonSchemaFilter(lookup, registry, mapper);
    MockServerWebExchange exchange = jsonRequest("{\"name\":\"x\",\"count\":1}");

    filter.filter(exchange, ex -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
  }

  private MockServerWebExchange jsonRequest(String body) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.post("/r")
            .contentType(MediaType.APPLICATION_JSON)
            .contentLength(body.length())
            .body(body));
  }
}
