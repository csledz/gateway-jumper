// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;

/**
 * Configures tracing — propagation, OTLP export and span-name customization.
 *
 * <p>B3_MULTI propagation is configured via {@code application.yml}. OTLP export is activated with
 * the {@code otlp} Spring profile, or explicitly via {@code gateway.observability.tracing.otlp.enabled=true}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class TracingCustomizer {

  /**
   * Customises observation span names, mirroring jumper's behaviour but delegating redaction to
   * {@link SecretRedactor} so sensitive query params never reach spans.
   */
  @Bean
  public ObservationFilter observabilitySpanNameFilter(SecretRedactor redactor) {
    return context -> {
      switch (context.getName()) {
        case "http.client.requests" -> {
          String spanName = "unknown";
          if (context instanceof ClientRequestObservationContext cctx) {
            ClientRequest request = cctx.getRequest();
            if (request != null) {
              if (request.url().getPath().contains("token")) {
                spanName = "idp";
              } else if (request.headers().getFirst(HttpHeaders.AUTHORIZATION) != null) {
                spanName = "gateway";
              }
            }
          }
          context.setContextualName("outgoing request: " + spanName);
        }
        case "http.server.requests" -> context.setContextualName("incoming request");
        case "spring.cloud.gateway.http.client.requests" -> {
          if (context instanceof GatewayContext gctx) {
            ServerHttpRequest request = gctx.getRequest();
            gctx.setContextualName("outgoing request: provider");
            gctx.addHighCardinalityKeyValue(
                KeyValue.of("http.uri", redactor.filterQueryParams(request.getURI().toString())));
            gctx.removeLowCardinalityKeyValue("spring.cloud.gateway.route.id");
            gctx.removeLowCardinalityKeyValue("spring.cloud.gateway.route.uri");
          }
        }
        default -> {
          // No customisation for other observations.
        }
      }
      return context;
    };
  }

  /**
   * OTLP gRPC exporter, activated either via {@code otlp} Spring profile or explicit property.
   *
   * <p>Sibling modules can replace this bean by declaring their own {@link OpenTelemetrySdk} bean.
   */
  @Bean(destroyMethod = "close")
  @Profile("otlp")
  @ConditionalOnClass(OtlpGrpcSpanExporter.class)
  @ConditionalOnProperty(
      prefix = "gateway.observability.tracing.otlp",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean
  public OpenTelemetrySdk otlpTracerProvider(
      @Value("${gateway.observability.tracing.otlp.endpoint:http://localhost:4317}") String endpoint,
      @Value("${spring.application.name:gateway-core}") String serviceName) {
    log.info("OTLP tracing enabled (endpoint={}, service={})", endpoint, serviceName);
    OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), serviceName)));
    SdkTracerProvider tp =
        SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();
    return OpenTelemetrySdk.builder().setTracerProvider(tp).build();
  }
}
