// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.telekom.gateway.mesh_federation.failover.FailoverSelector;
import io.telekom.gateway.mesh_federation.filter.MeshFederationFilter;
import io.telekom.gateway.mesh_federation.health.RedisZoneHealthPubSub;
import io.telekom.gateway.mesh_federation.health.ZoneHealthRegistry;
import io.telekom.gateway.mesh_federation.peer.MeshPeerRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/** Wires the mesh-federation beans. */
@Configuration
@EnableConfigurationProperties(MeshFederationProperties.class)
public class MeshFederationConfig {

  @Bean
  @ConditionalOnMissingBean
  public Clock meshClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public MeterRegistry meshMeterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  public ZoneHealthRegistry zoneHealthRegistry(MeterRegistry meters, Clock clock) {
    return new ZoneHealthRegistry(meters, clock);
  }

  @Bean
  public FailoverSelector failoverSelector(ZoneHealthRegistry registry) {
    return new FailoverSelector(registry);
  }

  @Bean
  public MeshPeerRegistry meshPeerRegistry() {
    return new MeshPeerRegistry();
  }

  @Bean
  public MeshFederationFilter meshFederationFilter(
      FailoverSelector selector, MeshPeerRegistry peers, MeshFederationProperties props) {
    return new MeshFederationFilter(selector, peers, props);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "gateway.mesh",
      name = "pubsub-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RedisZoneHealthPubSub redisZoneHealthPubSub(
      ReactiveRedisConnectionFactory connections,
      ReactiveStringRedisTemplate template,
      ZoneHealthRegistry registry,
      MeshFederationProperties props,
      ObjectMapper mapper,
      Clock clock) {
    return new RedisZoneHealthPubSub(connections, template, registry, props, mapper, clock);
  }
}
