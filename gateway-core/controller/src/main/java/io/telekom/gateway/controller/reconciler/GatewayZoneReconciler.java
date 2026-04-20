// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayZone;
import io.telekom.gateway.controller.snapshot.ConfigSnapshotEvent;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Watches {@link GatewayZone} CRDs. Unlike other CRDs, zones do not need a zone label — the zone is
 * itself the subject and publishes via {@code spec.zoneName}.
 */
@Component
public class GatewayZoneReconciler extends AbstractReconciler<GatewayZone> {

  private final ResourceCache cache;

  public GatewayZoneReconciler(
      KubernetesClient kubernetesClient,
      ApplicationEventPublisher eventPublisher,
      ResourceCache cache) {
    super(kubernetesClient, eventPublisher);
    this.cache = cache;
  }

  @Override
  protected SharedIndexInformer<GatewayZone> createInformer() {
    return kubernetesClient.resources(GatewayZone.class).inAnyNamespace().inform();
  }

  @Override
  protected String kind() {
    return "GatewayZone";
  }

  @Override
  protected void cacheUpsert(GatewayZone resource) {
    cache.upsertZone(resource);
  }

  @Override
  protected void cacheRemove(GatewayZone resource) {
    cache.removeZone(resource);
  }

  @Override
  protected void publish(GatewayZone resource, String cause) {
    String zone =
        resource.getSpec() != null && resource.getSpec().getZoneName() != null
            ? resource.getSpec().getZoneName()
            : resource.getZone();
    if (zone == null) {
      return;
    }
    eventPublisher.publishEvent(
        new ConfigSnapshotEvent(
            this,
            zone,
            kind()
                + ":"
                + cause
                + ":"
                + (resource.getMetadata() != null ? resource.getMetadata().getName() : "?")));
  }
}
