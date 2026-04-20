// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Cucumber step definitions for the OTel pipeline feature.
 *
 * <p>Each scenario resets the in-memory exporters before execution so the suite is
 * order-independent.
 */
public class OtelPipelineSteps {

  private static final Logger LOG = LoggerFactory.getLogger(OtelPipelineSteps.class);

  @Autowired private InMemorySpanExporter spanExporter;
  @Autowired private InMemoryMetricExporter metricExporter;
  @Autowired private InMemoryLogRecordExporter logRecordExporter;
  @Autowired private OtelTestApplication.MetricFlusher metricFlusher;
  @Autowired private OtelResourceCustomizer resourceCustomizer;
  @Autowired private ReactorOtelContext reactorOtelContext;

  @LocalServerPort private int port;

  private ResponseEntity<String> lastResponse;
  private String lastInboundB3TraceId;

  @Before
  public void beforeEach() {
    spanExporter.reset();
    metricExporter.reset();
    logRecordExporter.reset();
    lastResponse = null;
    lastInboundB3TraceId = null;
  }

  @Given("the gateway-core otel module is wired")
  public void moduleWired() {
    assertThat(reactorOtelContext.isInstalled())
        .as("reactor hook should be installed when the module is wired")
        .isTrue();
    assertThat(resourceCustomizer.getZone()).isEqualTo("test-zone");
    assertThat(resourceCustomizer.getRealm()).isEqualTo("test-realm");
  }

  @When("a client calls the demo handler")
  public void callDemo() {
    lastResponse =
        newClient()
            .get()
            .uri("/hello")
            .retrieve()
            .toEntity(String.class)
            .block(Duration.ofSeconds(5));
    assertThat(lastResponse).isNotNull();
    assertThat(lastResponse.getStatusCode().is2xxSuccessful()).isTrue();
  }

  @When("a client calls the demo handler with a B3 multi-header trace context")
  public void callDemoWithB3() {
    lastInboundB3TraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";
    lastResponse =
        newClient()
            .get()
            .uri("/hello")
            .header("X-B3-TraceId", lastInboundB3TraceId)
            .header("X-B3-SpanId", spanId)
            .header("X-B3-Sampled", "1")
            .retrieve()
            .toEntity(String.class)
            .block(Duration.ofSeconds(5));
    assertThat(lastResponse).isNotNull();
    assertThat(lastResponse.getStatusCode().is2xxSuccessful()).isTrue();
  }

  @Then("a span with service.name {string} is exported")
  public void spanWithServiceName(String expected) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              List<SpanData> spans = spanExporter.getFinishedSpanItems();
              assertThat(spans).isNotEmpty();
              SpanData span = spans.get(spans.size() - 1);
              assertThat(span.getName()).isEqualTo("hello-handler");
              assertThat(span.getResource().getAttribute(OtelResourceCustomizer.SERVICE_NAME))
                  .isEqualTo(expected);
              assertThat(span.getResource().getAttribute(OtelResourceCustomizer.SERVICE_NAMESPACE))
                  .isEqualTo("gateway-core");
              assertThat(span.getResource().getAttribute(OtelResourceCustomizer.GATEWAY_ZONE))
                  .isEqualTo("test-zone");
            });
  }

  @Then("the demo counter metric is exported")
  public void counterExported() {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              metricFlusher.flush();
              List<MetricData> metrics = metricExporter.getFinishedMetricItems();
              assertThat(metrics)
                  .as("expected the demo counter to be present in the exported metric batch")
                  .anyMatch(m -> "gateway.demo.requests".equals(m.getName()));
            });
  }

  @Then("a log record is bridged through the OTel logger")
  public void logBridged() {
    LOG.info(
        "cucumber-otel-bridge-check zone={} realm={}",
        resourceCustomizer.getZone(),
        resourceCustomizer.getRealm());
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(logRecordExporter.getFinishedLogRecordItems())
                    .as("Logback → OTel bridge should capture log records")
                    .anyMatch(
                        r ->
                            r.getBodyValue() != null
                                && r.getBodyValue()
                                    .asString()
                                    .contains("cucumber-otel-bridge-check")));
  }

  @Then("the exported span carries the inbound B3 trace id")
  public void b3InteropVerified() {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              List<SpanData> spans = spanExporter.getFinishedSpanItems();
              assertThat(spans).isNotEmpty();
              SpanData span = spans.get(spans.size() - 1);
              SpanContext parent = span.getParentSpanContext();
              assertThat(parent.isValid())
                  .as("span should have a parent propagated from the inbound B3 headers")
                  .isTrue();
              assertThat(span.getTraceId()).isEqualTo(lastInboundB3TraceId);
            });
  }

  private WebClient newClient() {
    return WebClient.builder()
        .baseUrl("http://127.0.0.1:" + port)
        .defaultHeaders(HttpHeaders::clear)
        .build();
  }
}
