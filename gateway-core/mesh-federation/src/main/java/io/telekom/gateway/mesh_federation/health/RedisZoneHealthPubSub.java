// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.telekom.gateway.mesh_federation.config.MeshFederationProperties;
import io.telekom.gateway.mesh_federation.model.ZoneHealth;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Owns the Redis pub/sub side of mesh-federation: subscribes to heartbeats from peer zones,
 * publishes the local heartbeat on a fixed interval, and sweeps staleness on the same cadence.
 *
 * <p>Connection loss is logged and retried with exponential backoff (capped at 30s). Records are
 * not persisted beyond the in-memory registry; Redis is the transport only.
 */
@Slf4j
public class RedisZoneHealthPubSub {

  private final ReactiveRedisConnectionFactory connections;
  private final org.springframework.data.redis.core.ReactiveStringRedisTemplate template;
  private final ZoneHealthRegistry registry;
  private final MeshFederationProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ChannelTopic topic;
  private final Disposable.Composite subs = Disposables.composite();
  private ReactiveRedisMessageListenerContainer listener;

  public RedisZoneHealthPubSub(
      ReactiveRedisConnectionFactory connections,
      org.springframework.data.redis.core.ReactiveStringRedisTemplate template,
      ZoneHealthRegistry registry,
      MeshFederationProperties props,
      ObjectMapper mapper,
      Clock clock) {
    this.connections = connections;
    this.template = template;
    this.registry = registry;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
    this.topic = new ChannelTopic(props.channel());
  }

  @PostConstruct
  public void start() {
    listener = new ReactiveRedisMessageListenerContainer(connections);
    subs.add(
        listener
            .receive(topic)
            .map(msg -> msg.getMessage())
            .flatMap(this::decode)
            .doOnNext(registry::accept)
            .retryWhen(
                Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofSeconds(30)))
            .subscribe(
                update -> log.trace("ingested heartbeat for zone={}", update.zone()),
                err -> log.error("zone-status subscriber terminated: {}", err.toString())));

    long intervalMs = props.healthIntervalSeconds() * 1000L;
    long staleMs = props.staleAfterSeconds() * 1000L;
    subs.add(
        Flux.interval(Duration.ofMillis(intervalMs))
            .onBackpressureDrop()
            .concatMap(
                tick ->
                    publishLocal()
                        .doOnSuccess(unused -> registry.sweepStaleness(staleMs))
                        .onErrorResume(
                            err -> {
                              log.warn("heartbeat tick failed: {}", err.toString());
                              return Mono.empty();
                            }))
            .subscribe());
    log.info(
        "mesh-federation pub/sub started on channel={} local-zone={} interval={}s stale-after={}s",
        props.channel(),
        props.localZone(),
        props.healthIntervalSeconds(),
        props.staleAfterSeconds());
  }

  @PreDestroy
  public void stop() {
    subs.dispose();
    if (listener != null) {
      listener.destroy();
    }
  }

  /** Publish one local heartbeat. Exposed for test-driven scenarios. */
  public Mono<Long> publishLocal() {
    ZoneHealth record = new ZoneHealth(props.localZone(), true, clock.millis(), props.localZone());
    return encode(record).flatMap(json -> template.convertAndSend(props.channel(), json));
  }

  private Mono<ZoneHealth> decode(String json) {
    try {
      return Mono.just(mapper.readValue(json, ZoneHealth.class));
    } catch (Exception e) {
      log.warn("skipping malformed zone-status payload: {}", e.getMessage());
      return Mono.empty();
    }
  }

  private Mono<String> encode(ZoneHealth record) {
    try {
      return Mono.just(mapper.writeValueAsString(record));
    } catch (Exception e) {
      return Mono.error(e);
    }
  }
}
