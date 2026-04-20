// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.telekom.gateway.otel.logs.OtelLogbackBridge;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration entry point for the gateway-core OpenTelemetry module.
 *
 * <p>Gated on {@code gateway.otel.enabled} (default {@code true}). When present on the classpath
 * this module:
 *
 * <ul>
 *   <li>Builds an {@link OpenTelemetrySdk} via {@link AutoConfiguredOpenTelemetrySdk} if no other
 *       bean already provides one (e.g. the instrumentation Spring Boot starter).
 *   <li>Contributes resource attributes via {@link OtelResourceCustomizer}.
 *   <li>Pins the global propagator list to {@code tracecontext,baggage,b3multi} via {@link
 *       OtelPropagatorsConfig} so interop with the legacy jumper B3 stack is preserved.
 *   <li>Installs Reactor operator hooks via {@link ReactorOtelContext}.
 *   <li>Optionally bridges Logback to the OTel log pipeline via {@link OtelLogbackBridge} when
 *       {@code gateway.otel.logs.enabled=true}.
 * </ul>
 *
 * <p>No literal authentication tokens are read, emitted, or held in this class. Auth to an OTLP
 * collector — if the deployment needs it — is configured through {@code OTEL_EXPORTER_OTLP_HEADERS}
 * by the deployment pipeline.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "gateway.otel",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Import({
  OtelResourceCustomizer.class,
  OtelPropagatorsConfig.class,
  ReactorOtelContext.class,
  OtelLogbackBridge.class
})
public class OtelAutoConfiguration {

  @Value("${gateway.otel.sdk.autoconfigure:true}")
  private boolean autoconfigureSdk;

  @PostConstruct
  void announce() {
    log.info(
        "gateway-core OTel module active (sdk-autoconfigure={}) — traces/metrics/logs wired",
        autoconfigureSdk);
  }

  /**
   * Provides an {@link OpenTelemetrySdk} bean only if the upstream instrumentation starter has not
   * already created one. The {@link AutoConfiguredOpenTelemetrySdk} entry point reads every OTel
   * SDK setting from env/system properties — which keeps the {@link
   * io.opentelemetry.api.OpenTelemetry} contract hermetic (no literals in Java).
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(OpenTelemetry.class)
  @ConditionalOnProperty(
      prefix = "gateway.otel.sdk",
      name = "autoconfigure",
      havingValue = "true",
      matchIfMissing = true)
  public OpenTelemetrySdk gatewayOtelSdk(
      OtelResourceCustomizer resourceCustomizer, OtelPropagatorsConfig propagatorsConfig) {
    AutoConfiguredOpenTelemetrySdk sdk =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addResourceCustomizer(resourceCustomizer)
            .addPropagatorCustomizer(propagatorsConfig)
            .setResultAsGlobal()
            .build();
    OpenTelemetrySdk openTelemetrySdk = sdk.getOpenTelemetrySdk();
    log.info(
        "OpenTelemetrySdk auto-configured (tracer-provider={})",
        openTelemetrySdk.getSdkTracerProvider());
    return openTelemetrySdk;
  }
}
