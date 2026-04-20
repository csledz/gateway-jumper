// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayMeshPeer;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Watches {@link GatewayMeshPeer} CRDs. */
@Component
public class GatewayMeshPeerReconciler extends AbstractReconciler<GatewayMeshPeer> {

  private final ResourceCache cache;

  public GatewayMeshPeerReconciler(
      KubernetesClient kubernetesClient,
      ApplicationEventPublisher eventPublisher,
      ResourceCache cache) {
    super(kubernetesClient, eventPublisher);
    this.cache = cache;
  }

  @Override
  protected SharedIndexInformer<GatewayMeshPeer> createInformer() {
    return kubernetesClient.resources(GatewayMeshPeer.class).inAnyNamespace().inform();
  }

  @Override
  protected String kind() {
    return "GatewayMeshPeer";
  }

  @Override
  protected void cacheUpsert(GatewayMeshPeer resource) {
    cache.upsertMeshPeer(resource);
  }

  @Override
  protected void cacheRemove(GatewayMeshPeer resource) {
    cache.removeMeshPeer(resource);
  }
}
