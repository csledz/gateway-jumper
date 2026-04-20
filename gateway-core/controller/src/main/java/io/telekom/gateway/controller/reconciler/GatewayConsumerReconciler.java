// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayConsumer;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Watches {@link GatewayConsumer} CRDs. */
@Component
public class GatewayConsumerReconciler extends AbstractReconciler<GatewayConsumer> {

  private final ResourceCache cache;

  public GatewayConsumerReconciler(
      KubernetesClient kubernetesClient,
      ApplicationEventPublisher eventPublisher,
      ResourceCache cache) {
    super(kubernetesClient, eventPublisher);
    this.cache = cache;
  }

  @Override
  protected SharedIndexInformer<GatewayConsumer> createInformer() {
    return kubernetesClient.resources(GatewayConsumer.class).inAnyNamespace().inform();
  }

  @Override
  protected String kind() {
    return "GatewayConsumer";
  }

  @Override
  protected void cacheUpsert(GatewayConsumer resource) {
    cache.upsertConsumer(resource);
  }

  @Override
  protected void cacheRemove(GatewayConsumer resource) {
    cache.removeConsumer(resource);
  }
}
