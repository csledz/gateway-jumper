// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayRoute;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Watches {@link GatewayRoute} CRDs and feeds the resource cache. */
@Component
public class GatewayRouteReconciler extends AbstractReconciler<GatewayRoute> {

  private final ResourceCache cache;

  public GatewayRouteReconciler(
      KubernetesClient kubernetesClient,
      ApplicationEventPublisher eventPublisher,
      ResourceCache cache) {
    super(kubernetesClient, eventPublisher);
    this.cache = cache;
  }

  @Override
  protected SharedIndexInformer<GatewayRoute> createInformer() {
    return kubernetesClient.resources(GatewayRoute.class).inAnyNamespace().inform();
  }

  @Override
  protected String kind() {
    return "GatewayRoute";
  }

  @Override
  protected void cacheUpsert(GatewayRoute resource) {
    cache.upsertRoute(resource);
  }

  @Override
  protected void cacheRemove(GatewayRoute resource) {
    cache.removeRoute(resource);
  }
}
