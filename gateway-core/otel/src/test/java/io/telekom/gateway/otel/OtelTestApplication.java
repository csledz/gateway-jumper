// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Self-contained Spring Boot application used by the Cucumber feature test. Wires:
 *
 * <ul>
 *   <li>An {@link OpenTelemetrySdk} with in-memory span / metric / log exporters so scenarios can
 *       assert on the exact data the module produced.
 *   <li>A minimal WebFlux handler at {@code /hello} that creates a span and increments a counter,
 *       exercising the reactor hook and resource customizer.
 * </ul>
 *
 * <p>Nothing in this application contacts a remote collector; the assertions run against the
 * in-memory exporters. The Testcontainers-backed collector is started separately in {@link
 * CollectorContainer} for the collector-acceptance scenario.
 */
@SpringBootApplication
public class OtelTestApplication {

  public static final String TRACER_NAME = "gateway-core-otel-test";

  public static void main(String[] args) {
    SpringApplication.run(OtelTestApplication.class, args);
  }

  /** Span exporter — scenarios clear and assert on this bean. */
  @Bean
  public InMemorySpanExporter inMemorySpanExporter() {
    return InMemorySpanExporter.create();
  }

  /** Metric exporter — paired with a {@link PeriodicMetricReader} so metrics are flushable. */
  @Bean
  public InMemoryMetricExporter inMemoryMetricExporter() {
    return InMemoryMetricExporter.create();
  }

  /** Log exporter — captured by the Logback OTel appender. */
  @Bean
  public InMemoryLogRecordExporter inMemoryLogRecordExporter() {
    return InMemoryLogRecordExporter.create();
  }

  /**
   * Test-scoped OTel SDK. Resource attributes flow through {@link OtelResourceCustomizer},
   * propagators through {@link OtelPropagatorsConfig} — exactly the wiring real deployments get,
   * minus the OTLP exporter.
   */
  @Bean(destroyMethod = "close")
  public OpenTelemetrySdk testOpenTelemetrySdk(
      OtelResourceCustomizer resourceCustomizer,
      OtelPropagatorsConfig propagatorsConfig,
      InMemorySpanExporter spanExporter,
      InMemoryMetricExporter metricExporter,
      InMemoryLogRecordExporter logRecordExporter) {
    Resource resource = resourceCustomizer.apply(Resource.getDefault(), null);

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();

    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader.builder(metricExporter)
                    .setInterval(Duration.ofMillis(100))
                    .build())
            .build();

    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logRecordExporter))
            .build();

    ContextPropagators propagators = ContextPropagators.create(propagatorsConfig.apply(null, null));

    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setMeterProvider(meterProvider)
        .setLoggerProvider(loggerProvider)
        .setPropagators(propagators)
        .build();
  }

  // The OpenTelemetrySdk bean is itself an OpenTelemetry; Spring autowires it for
  // any injection point that wants the OpenTelemetry type, so we deliberately do
  // not expose a second bean here — that would cause a NoUniqueBeanDefinitionException.

  /**
   * WebFlux demo handler. Creates a child span of whatever OTel context arrived on the request (so
   * the B3 → W3C bridge can be asserted) and emits a counter metric.
   */
  @Bean
  public RouterFunction<ServerResponse> demoRoute(OpenTelemetry openTelemetry) {
    Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
    io.opentelemetry.api.metrics.LongCounter counter =
        openTelemetry
            .getMeter(TRACER_NAME)
            .counterBuilder("gateway.demo.requests")
            .setDescription("Demo counter used by the Cucumber pipeline test")
            .setUnit("1")
            .build();

    return RouterFunctions.route()
        .GET(
            "/hello",
            request -> {
              Context extracted =
                  openTelemetry
                      .getPropagators()
                      .getTextMapPropagator()
                      .extract(Context.current(), request.headers().asHttpHeaders(), HEADER_GETTER);
              Span span =
                  tracer
                      .spanBuilder("hello-handler")
                      .setParent(extracted)
                      .setAttribute(AttributeKey.stringKey("gateway.demo.route"), "/hello")
                      .startSpan();
              try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
                counter.add(1);
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .bodyValue("ok")
                    .doFinally(s -> span.end());
              }
            })
        .build();
  }

  /** Accessor bean for tests to force-flush metrics without reaching into the SDK. */
  @Bean
  public MetricFlusher metricFlusher(OpenTelemetrySdk sdk) {
    return new MetricFlusher(sdk);
  }

  private static final TextMapGetter<org.springframework.http.HttpHeaders> HEADER_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(org.springframework.http.HttpHeaders carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(org.springframework.http.HttpHeaders carrier, String key) {
          if (carrier == null) {
            return null;
          }
          return carrier.getFirst(key);
        }
      };

  /** Force-flushes the SDK meter provider so Cucumber assertions don't race the push interval. */
  public static final class MetricFlusher {
    private final OpenTelemetrySdk sdk;

    MetricFlusher(OpenTelemetrySdk sdk) {
      this.sdk = sdk;
    }

    public void flush() {
      sdk.getSdkMeterProvider().forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}
