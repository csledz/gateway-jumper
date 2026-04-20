// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayPolicy;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Watches {@link GatewayPolicy} CRDs. */
@Component
public class GatewayPolicyReconciler extends AbstractReconciler<GatewayPolicy> {

  private final ResourceCache cache;

  public GatewayPolicyReconciler(
      KubernetesClient kubernetesClient,
      ApplicationEventPublisher eventPublisher,
      ResourceCache cache) {
    super(kubernetesClient, eventPublisher);
    this.cache = cache;
  }

  @Override
  protected SharedIndexInformer<GatewayPolicy> createInformer() {
    return kubernetesClient.resources(GatewayPolicy.class).inAnyNamespace().inform();
  }

  @Override
  protected String kind() {
    return "GatewayPolicy";
  }

  @Override
  protected void cacheUpsert(GatewayPolicy resource) {
    cache.upsertPolicy(resource);
  }

  @Override
  protected void cacheRemove(GatewayPolicy resource) {
    cache.removePolicy(resource);
  }
}
