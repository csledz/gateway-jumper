// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.push;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.telekom.gateway.controller.snapshot.ConfigSnapshot;
import io.telekom.gateway.controller.snapshot.ConfigSnapshotEvent;
import io.telekom.gateway.controller.snapshot.SnapshotBuilder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Fans out {@link ConfigSnapshot} payloads to every registered data-plane pod.
 *
 * <p>Retry strategy follows the pattern used in jumper's {@code RedisZoneHealthStatusService} /
 * {@code UpstreamOAuthFilter}: bounded retry with fixed backoff via {@code spring-retry}. Each URL
 * is attempted independently so one slow pod does not block the rest.
 *
 * <h3>Push API contract (POST / application/json)</h3>
 *
 * <pre>
 * POST {dataPlaneUrl}/config
 * Content-Type: application/json
 * body: ConfigSnapshot  ({@link ConfigSnapshot})
 *   { schemaVersion: int,
 *     snapshotId:   string (uuid),
 *     generatedAt:  ISO-8601 timestamp,
 *     zone:         string,
 *     zoneSpec:     GatewayZone,
 *     routes:       [GatewayRoute],
 *     consumers:    [GatewayConsumer],
 *     credentials:  [GatewayCredential],
 *     meshPeers:    [GatewayMeshPeer],
 *     policies:     [GatewayPolicy] }
 *
 * Expected responses:
 *   2xx -> success, snapshot accepted
 *   5xx, connection errors -> retryable (exponential backoff, max 3 attempts)
 *   4xx -> permanent failure, not retried
 * </pre>
 */
@Slf4j
@Service
public class DataPlanePushService {

  private final List<String> dataPlaneUrls = new CopyOnWriteArrayList<>();
  private final SnapshotBuilder snapshotBuilder;
  private final DataPlaneHttpClient httpClient;
  private final Counter pushSuccess;
  private final Counter pushFailure;

  public DataPlanePushService(
      SnapshotBuilder snapshotBuilder,
      DataPlaneHttpClient httpClient,
      MeterRegistry meterRegistry,
      @Value("${controller.dataplane.urls:}") List<String> configuredUrls) {
    this.snapshotBuilder = snapshotBuilder;
    this.httpClient = httpClient;
    this.pushSuccess = meterRegistry.counter("controller.push.success");
    this.pushFailure = meterRegistry.counter("controller.push.failure");
    if (configuredUrls != null) {
      configuredUrls.stream().filter(u -> u != null && !u.isBlank()).forEach(dataPlaneUrls::add);
    }
  }

  public void registerDataPlane(String url) {
    if (!dataPlaneUrls.contains(url)) {
      log.info("Registering data-plane URL {}", url);
      dataPlaneUrls.add(url);
    }
  }

  public List<String> getDataPlaneUrls() {
    return List.copyOf(dataPlaneUrls);
  }

  @Async
  @EventListener
  public void onSnapshotEvent(ConfigSnapshotEvent event) {
    log.info("Reacting to snapshot event for zone={} cause={}", event.getZone(), event.getCause());
    pushSnapshotForZone(event.getZone());
  }

  public void pushSnapshotForZone(String zone) {
    ConfigSnapshot snap = snapshotBuilder.buildForZone(zone);
    push(snap);
  }

  public void push(ConfigSnapshot snap) {
    if (dataPlaneUrls.isEmpty()) {
      log.debug("No data-plane URLs registered; skipping push for zone {}", snap.getZone());
      return;
    }
    for (String url : dataPlaneUrls) {
      try {
        httpClient.pushTo(url, snap);
        pushSuccess.increment();
      } catch (Exception e) {
        pushFailure.increment();
        log.error(
            "Push to data-plane {} failed after retries for zone {}: {}",
            url,
            snap.getZone(),
            e.getMessage());
      }
    }
  }
}
