// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.telekom.gateway.controller.api.GatewayResource;
import io.telekom.gateway.controller.snapshot.ConfigSnapshotEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Common boilerplate for a reconciler: on startup register an informer with a {@link
 * ResourceEventHandler} that calls {@link #onAdd}, {@link #onUpdate}, {@link #onDelete}; those emit
 * a {@link ConfigSnapshotEvent}. Subclasses supply the informer and the upsert/remove actions.
 */
@Slf4j
public abstract class AbstractReconciler<T extends GatewayResource<?>> {

  protected final KubernetesClient kubernetesClient;
  protected final ApplicationEventPublisher eventPublisher;

  private SharedIndexInformer<T> informer;

  protected AbstractReconciler(
      KubernetesClient kubernetesClient, ApplicationEventPublisher eventPublisher) {
    this.kubernetesClient = kubernetesClient;
    this.eventPublisher = eventPublisher;
  }

  protected abstract SharedIndexInformer<T> createInformer();

  protected abstract String kind();

  protected abstract void cacheUpsert(T resource);

  protected abstract void cacheRemove(T resource);

  @PostConstruct
  public void start() {
    try {
      this.informer = createInformer();
      informer.addEventHandler(
          new ResourceEventHandler<>() {
            @Override
            public void onAdd(T obj) {
              log.info("{} ADD {}", kind(), name(obj));
              cacheUpsert(obj);
              publish(obj, "add");
            }

            @Override
            public void onUpdate(T oldObj, T newObj) {
              log.info("{} UPDATE {}", kind(), name(newObj));
              cacheUpsert(newObj);
              publish(newObj, "update");
            }

            @Override
            public void onDelete(T obj, boolean deletedFinalStateUnknown) {
              log.info("{} DELETE {}", kind(), name(obj));
              cacheRemove(obj);
              publish(obj, "delete");
            }
          });
      informer.start();
      log.info("{} reconciler started", kind());
    } catch (Exception e) {
      log.warn("{} reconciler failed to start (continuing): {}", kind(), e.getMessage());
    }
  }

  @PreDestroy
  public void stop() {
    if (informer != null) {
      try {
        informer.stop();
      } catch (Exception ignored) {
        // best effort
      }
    }
  }

  protected void publish(T resource, String cause) {
    String zone = resource.getZone();
    if (zone == null) {
      log.debug("{} {} has no zone label; skipping event", kind(), name(resource));
      return;
    }
    eventPublisher.publishEvent(
        new ConfigSnapshotEvent(this, zone, kind() + ":" + cause + ":" + name(resource)));
  }

  private String name(T r) {
    return r.getMetadata() != null ? r.getMetadata().getName() : "?";
  }
}
