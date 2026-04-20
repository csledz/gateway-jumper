// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fixes the OTel propagator chain to {@code tracecontext, baggage, b3multi}.
 *
 * <p>Why B3 is still in there: jumper-era services propagate trace context via B3 multi-header (see
 * the jumper {@code TracingConfiguration}). Inbound requests from those services must keep their
 * trace identity as they cross the gateway-core boundary; outbound requests need to present both
 * formats so we don't break peers that only parse one.
 *
 * <p>Also registered as a {@link BiFunction} in the shape {@link
 * io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk#addPropagatorCustomizer}
 * expects.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class OtelPropagatorsConfig
    implements BiFunction<TextMapPropagator, ConfigProperties, TextMapPropagator> {

  /** The composite propagator used for both injection and extraction. */
  @Bean
  public ContextPropagators gatewayOtelContextPropagators() {
    TextMapPropagator composite = buildComposite();
    log.info(
        "OTel propagators pinned to tracecontext + baggage + b3multi (jumper B3 interop"
            + " preserved)");
    return ContextPropagators.create(composite);
  }

  @Override
  public TextMapPropagator apply(TextMapPropagator existing, ConfigProperties configProperties) {
    return buildComposite();
  }

  private static TextMapPropagator buildComposite() {
    return TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance(),
        B3Propagator.injectingMultiHeaders());
  }
}
