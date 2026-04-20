// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.api.ValidationPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Validates request bodies against a JSON Schema referenced in the route policy. Only engages when
 * (a) a policy with a {@code schemaRef} is attached and (b) the request Content-Type is {@code
 * application/json}. Reports violations as RFC 7807 problem+json.
 */
@Slf4j
public class JsonSchemaFilter implements WebFilter, Ordered {

  public static final int ORDER = 140;
  private final PolicyLookup lookup;
  private final JsonSchemaRegistry registry;
  private final ObjectMapper mapper;

  public JsonSchemaFilter(PolicyLookup lookup, JsonSchemaRegistry registry, ObjectMapper mapper) {
    this.lookup = lookup;
    this.registry = registry;
    this.mapper = mapper;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String schemaRef =
        lookup.find(exchange).map(ValidationPolicy::schemaRef).filter(s -> s != null).orElse(null);
    if (schemaRef == null) {
      return chain.filter(exchange);
    }
    MediaType contentType = exchange.getRequest().getHeaders().getContentType();
    if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
      return chain.filter(exchange);
    }
    JsonSchema schema = registry.resolve(schemaRef).orElse(null);
    if (schema == null) {
      return chain.filter(exchange);
    }
    return DataBufferUtils.join(exchange.getRequest().getBody())
        .flatMap(
            buf -> {
              byte[] bytes = new byte[buf.readableByteCount()];
              buf.read(bytes);
              DataBufferUtils.release(buf);
              try {
                JsonNode tree = mapper.readTree(bytes);
                Set<ValidationMessage> messages = schema.validate(tree);
                if (messages.isEmpty()) {
                  return chain.filter(
                      exchange.mutate().request(replay(exchange.getRequest(), bytes)).build());
                }
                return reject(exchange, messages);
              } catch (Exception e) {
                return reject(exchange, "invalid JSON body: " + e.getMessage());
              }
            })
        .switchIfEmpty(chain.filter(exchange));
  }

  private ServerHttpRequestDecorator replay(ServerHttpRequest original, byte[] bytes) {
    return new ServerHttpRequestDecorator(original) {
      @Override
      public Flux<DataBuffer> getBody() {
        return Flux.defer(
            () -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes.clone())));
      }
    };
  }

  private Mono<Void> reject(ServerWebExchange exchange, Set<ValidationMessage> messages) {
    String body =
        "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
            + "\"detail\":\"JSON schema validation failed\",\"violations\":["
            + messages.stream()
                .map(
                    m ->
                        "{\"pointer\":\""
                            + safe(
                                m.getInstanceLocation() == null
                                    ? ""
                                    : m.getInstanceLocation().toString())
                            + "\",\"message\":\""
                            + safe(m.getMessage())
                            + "\"}")
                .collect(Collectors.joining(","))
            + "]}";
    return writeProblem(exchange, body);
  }

  private Mono<Void> reject(ServerWebExchange exchange, String detail) {
    String body =
        "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\""
            + safe(detail)
            + "\"}";
    return writeProblem(exchange, body);
  }

  private Mono<Void> writeProblem(ServerWebExchange exchange, String body) {
    exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    DataBuffer buf =
        exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    exchange.getResponse().getHeaders().setContentLength(buf.readableByteCount());
    return exchange.getResponse().writeWith(Mono.just(buf));
  }

  private static String safe(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
