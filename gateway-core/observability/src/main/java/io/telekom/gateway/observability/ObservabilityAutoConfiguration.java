// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.telekom.gateway.observability.logging.ReactorMdcPropagation;
import io.telekom.gateway.observability.logging.StructuredLogbackConfig;
import io.telekom.gateway.observability.metrics.NettyServerMetrics;
import io.telekom.gateway.observability.metrics.RedMetricsFilter;
import io.telekom.gateway.observability.tracing.SecretRedactor;
import io.telekom.gateway.observability.tracing.TracingCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration entry point for the observability module.
 *
 * <p>Sibling modules attach transparently — adding this module to the classpath wires RED metrics,
 * B3/OTLP tracing, structured JSON logging and Netty server metrics.
 */
@AutoConfiguration
@Import({NettyServerMetrics.class, TracingCustomizer.class, StructuredLogbackConfig.class})
public class ObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SecretRedactor gatewayObservabilitySecretRedactor(
      @Value("${gateway.observability.tracing.filter-param-list:}")
          java.util.List<String> extraQueryFilters) {
    return new SecretRedactor(extraQueryFilters);
  }

  @Bean
  @ConditionalOnMissingBean
  public RedMetricsFilter gatewayObservabilityRedMetricsFilter(
      MeterRegistry registry,
      @Value("${gateway.observability.zone:${gateway.zone.name:default}}") String zone) {
    return new RedMetricsFilter(registry, zone);
  }

  @Bean
  @ConditionalOnMissingBean
  public ReactorMdcPropagation gatewayObservabilityReactorMdcPropagation() {
    return new ReactorMdcPropagation();
  }
}
