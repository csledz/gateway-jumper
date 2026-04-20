// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.health;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.telekom.gateway.mesh_federation.model.ZoneHealth;
import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe registry of the most recent {@link ZoneHealth} snapshot per zone.
 *
 * <p>Exposes one gauge per known zone ({@code gateway_mesh_zone_healthy{zone="..."}}) and a summary
 * gauge {@code gateway_mesh_zones_healthy_count}. Meter registration is idempotent — first sighting
 * of a zone wires its gauge; subsequent updates simply mutate the underlying boolean state that the
 * gauge reads from.
 */
@Slf4j
public class ZoneHealthRegistry {

  private final ConcurrentHashMap<String, ZoneHealth> byZone = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> gaugeState = new ConcurrentHashMap<>();
  private final MeterRegistry meters;
  private final Clock clock;

  public ZoneHealthRegistry(MeterRegistry meters, Clock clock) {
    this.meters = meters;
    this.clock = clock;
    meters.gauge("gateway_mesh_zones_healthy_count", byZone, this::countHealthy);
  }

  /** Merge an incoming heartbeat into the registry. Idempotent. */
  public void accept(ZoneHealth update) {
    byZone.put(update.zone(), update);
    gaugeState
        .computeIfAbsent(
            update.zone(),
            z -> {
              AtomicInteger state = new AtomicInteger();
              meters.gauge(
                  "gateway_mesh_zone_healthy",
                  java.util.List.of(Tag.of("zone", z)),
                  state,
                  AtomicInteger::get);
              return state;
            })
        .set(update.healthy() ? 1 : 0);
  }

  /**
   * Recompute health for any zone whose last-seen timestamp is older than {@code staleAfterMillis}.
   * A fresh heartbeat later flips the zone back to healthy.
   */
  public void sweepStaleness(long staleAfterMillis) {
    long now = clock.millis();
    byZone.forEach(
        (zone, snapshot) -> {
          if (snapshot.healthy() && now - snapshot.lastSeenEpochMs() > staleAfterMillis) {
            log.debug(
                "zone {} marked unhealthy: stale for {}ms", zone, now - snapshot.lastSeenEpochMs());
            accept(new ZoneHealth(zone, false, snapshot.lastSeenEpochMs(), snapshot.reportedBy()));
          }
        });
  }

  public boolean isHealthy(String zone) {
    ZoneHealth snapshot = byZone.get(zone);
    return snapshot != null && snapshot.healthy();
  }

  public Optional<ZoneHealth> snapshot(String zone) {
    return Optional.ofNullable(byZone.get(zone));
  }

  public Collection<ZoneHealth> all() {
    return java.util.Collections.unmodifiableCollection(byZone.values());
  }

  private double countHealthy(ConcurrentHashMap<String, ZoneHealth> m) {
    return m.values().stream().filter(ZoneHealth::healthy).count();
  }
}
