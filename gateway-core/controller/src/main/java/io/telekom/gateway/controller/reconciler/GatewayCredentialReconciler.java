// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayCredential;
import io.telekom.gateway.controller.snapshot.ResourceCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Watches {@link GatewayCredential} CRDs. */
@Component
public class GatewayCredentialReconciler extends AbstractReconciler<GatewayCredential> {

  private final ResourceCache cache;

  public GatewayCredentialReconciler(
      KubernetesClient kubernetesClient,
      ApplicationEventPublisher eventPublisher,
      ResourceCache cache) {
    super(kubernetesClient, eventPublisher);
    this.cache = cache;
  }

  @Override
  protected SharedIndexInformer<GatewayCredential> createInformer() {
    return kubernetesClient.resources(GatewayCredential.class).inAnyNamespace().inform();
  }

  @Override
  protected String kind() {
    return "GatewayCredential";
  }

  @Override
  protected void cacheUpsert(GatewayCredential resource) {
    cache.upsertCredential(resource);
  }

  @Override
  protected void cacheRemove(GatewayCredential resource) {
    cache.removeCredential(resource);
  }
}
