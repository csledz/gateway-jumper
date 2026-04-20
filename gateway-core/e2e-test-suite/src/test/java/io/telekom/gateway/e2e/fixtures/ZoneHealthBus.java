// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.fixtures;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import reactor.core.Disposable;

/**
 * Minimal port of the zone-health pub/sub contract. Publishes "UNHEALTHY:<zone>" messages to a
 * Redis channel, and each proxy keeps an in-memory skip-set used by the failover logic.
 *
 * <p>Real module will ship a richer {@code HealthEvent} schema, TTL, and health scoring. Here we
 * only need enough to drive {@code zone-failover.feature}.
 */
@Slf4j
public final class ZoneHealthBus {

  public static final String TOPIC = "gateway-core.zone-health";

  @Getter private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();
  private final ReactiveStringRedisTemplate redis;
  private Disposable sub;

  public ZoneHealthBus(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  public void start() {
    sub = redis.listenTo(ChannelTopic.of(TOPIC)).doOnNext(m -> apply(m.getMessage())).subscribe();
  }

  public void stop() {
    if (sub != null && !sub.isDisposed()) sub.dispose();
  }

  public void markUnhealthy(String zone) {
    redis.convertAndSend(TOPIC, "UNHEALTHY:" + zone).subscribe();
  }

  public void markHealthy(String zone) {
    redis.convertAndSend(TOPIC, "HEALTHY:" + zone).subscribe();
  }

  private void apply(String msg) {
    String[] parts = msg.split(":", 2);
    if (parts.length != 2) return;
    if ("UNHEALTHY".equals(parts[0])) {
      unhealthy.add(parts[1]);
    } else if ("HEALTHY".equals(parts[0])) {
      unhealthy.remove(parts[1]);
    }
    log.debug("zone-health applied: {} -> unhealthy={}", msg, unhealthy);
  }
}
