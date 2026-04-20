// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.consul;

import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Queries Consul's {@code /v1/health/service/<name>?passing=true} endpoint and returns healthy
 * instances. Uses {@link WebClient} directly rather than the heavy {@code consul-client} jar so the
 * default build stays slim; the {@code consul} Maven profile pulls in {@code
 * com.orbitz.consul:consul-client} for teams that want its richer API.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "gateway.service-discovery.consul",
    name = "enabled",
    havingValue = "true")
public class ConsulResolver implements ServiceResolver {

  public static final String SCHEME = "consul";

  private final WebClient web;

  public ConsulResolver(String baseUrl) {
    this(WebClient.builder().baseUrl(Objects.requireNonNull(baseUrl)).build());
  }

  public ConsulResolver(WebClient web) {
    this.web = Objects.requireNonNull(web);
  }

  @Override
  public String scheme() {
    return SCHEME;
  }

  @Override
  public Mono<List<ServiceEndpoint>> resolve(ServiceRef ref) {
    return web.get()
        .uri("/v1/health/service/{name}?passing=true", ref.name())
        .retrieve()
        .bodyToFlux(ConsulHealthEntry.class)
        .map(this::toEndpoint)
        .collectList()
        .doOnError(e -> log.warn("Consul lookup failed for {}: {}", ref.name(), e.toString()))
        .onErrorReturn(List.of());
  }

  private ServiceEndpoint toEndpoint(ConsulHealthEntry e) {
    String host =
        (e.service != null && e.service.address != null && !e.service.address.isBlank())
            ? e.service.address
            : (e.node != null ? e.node.address : null);
    int port = e.service != null && e.service.port > 0 ? e.service.port : 80;
    int weight = e.service != null && e.service.weights != null ? e.service.weights.passing : 1;
    return new ServiceEndpoint(host, port, "http", true, Math.max(weight, 0));
  }

  /** Minimal DTO — matches Consul's {@code /v1/health/service} response. */
  public static final class ConsulHealthEntry {
    @com.fasterxml.jackson.annotation.JsonProperty("Node")
    public Node node;

    @com.fasterxml.jackson.annotation.JsonProperty("Service")
    public Service service;

    public static final class Node {
      @com.fasterxml.jackson.annotation.JsonProperty("Address")
      public String address;
    }

    public static final class Service {
      @com.fasterxml.jackson.annotation.JsonProperty("Address")
      public String address;

      @com.fasterxml.jackson.annotation.JsonProperty("Port")
      public int port;

      @com.fasterxml.jackson.annotation.JsonProperty("Weights")
      public Weights weights;
    }

    public static final class Weights {
      @com.fasterxml.jackson.annotation.JsonProperty("Passing")
      public int passing = 1;
    }
  }
}
