// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import java.util.UUID;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Contributes gateway-core resource attributes to every span, metric data point, and log record the
 * SDK produces.
 *
 * <p>Values come from environment variables / Spring properties only — nothing is hardcoded. The
 * contract is:
 *
 * <ul>
 *   <li>{@code service.name} — {@code ${spring.application.name}} or {@code
 *       gateway.otel.service.name}
 *   <li>{@code service.namespace} — {@code gateway-core} (landscape marker)
 *   <li>{@code service.instance.id} — pod name ({@code HOSTNAME}) falling back to a per-process
 *       UUID
 *   <li>{@code gateway.zone} — {@code ${gateway.zone.name}} — sourced from the zone operator
 *   <li>{@code gateway.realm} — {@code ${gateway.realm.name}} — tenancy / env marker
 * </ul>
 *
 * <p>The bean is both a Spring component (consumed by {@link OtelAutoConfiguration}) and a {@link
 * BiFunction} in the shape the {@link
 * io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk} resource customizer API
 * expects.
 */
@Slf4j
@Component
public class OtelResourceCustomizer implements BiFunction<Resource, ConfigProperties, Resource> {

  static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
  static final AttributeKey<String> SERVICE_NAMESPACE = AttributeKey.stringKey("service.namespace");
  static final AttributeKey<String> SERVICE_INSTANCE_ID =
      AttributeKey.stringKey("service.instance.id");
  static final AttributeKey<String> GATEWAY_ZONE = AttributeKey.stringKey("gateway.zone");
  static final AttributeKey<String> GATEWAY_REALM = AttributeKey.stringKey("gateway.realm");

  private static final String NAMESPACE = "gateway-core";

  private final String serviceName;
  private final String serviceInstanceId;
  private final String zone;
  private final String realm;

  public OtelResourceCustomizer(
      @Value("${gateway.otel.service.name:${spring.application.name:gateway-core}}")
          String serviceName,
      @Value("${gateway.otel.service.instance-id:${HOSTNAME:}}") String serviceInstanceIdRaw,
      @Value("${gateway.zone.name:${gateway.otel.zone:default}}") String zone,
      @Value("${gateway.realm.name:${gateway.otel.realm:default}}") String realm) {
    this.serviceName = serviceName;
    this.serviceInstanceId =
        (serviceInstanceIdRaw == null || serviceInstanceIdRaw.isBlank())
            ? UUID.randomUUID().toString()
            : serviceInstanceIdRaw;
    this.zone = zone;
    this.realm = realm;
  }

  @Override
  public Resource apply(Resource existing, ConfigProperties config) {
    ResourceBuilder builder =
        (existing == null ? Resource.getDefault() : existing)
            .toBuilder()
                .put(SERVICE_NAME, serviceName)
                .put(SERVICE_NAMESPACE, NAMESPACE)
                .put(SERVICE_INSTANCE_ID, serviceInstanceId)
                .put(GATEWAY_ZONE, zone)
                .put(GATEWAY_REALM, realm);
    Resource resource = builder.build();
    log.debug(
        "OTel resource customized: service.name={}, service.namespace={}, service.instance.id={},"
            + " gateway.zone={}, gateway.realm={}",
        serviceName,
        NAMESPACE,
        serviceInstanceId,
        zone,
        realm);
    return resource;
  }

  String getServiceName() {
    return serviceName;
  }

  String getServiceInstanceId() {
    return serviceInstanceId;
  }

  String getZone() {
    return zone;
  }

  String getRealm() {
    return realm;
  }
}
