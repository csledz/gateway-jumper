<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# Migrating the `gateway-core/observability` module to OTel-first

The sibling **`gateway-core/observability`** module currently ships a Brave +
Zipkin tracer, an optional OTLP exporter gated behind a Spring profile, and a
Micrometer Prometheus registry. With `gateway-core/otel` now present, the
observability module should become the OTel module's **policy / redaction /
dashboards layer**, not a second tracing backbone.

This document is prescriptive prose — a migration recipe for the author of the
observability module (or a follow-up PR) to apply. **Do not edit the
observability module from this branch.**

## Target end-state

1. The **OTel SDK is the single source of truth** for traces, metrics, and logs.
2. The observability module:
   - keeps `SecretRedactor` (applied via a span processor, not a Brave handler),
   - keeps `RedMetricsFilter` (Micrometer-side, unchanged),
   - keeps `NettyServerMetrics` (unchanged),
   - keeps `StructuredLogbackConfig` (stays — OTel logs bridge is additive, not
     replacing JSON console output),
   - keeps `ReactorMdcPropagation` (stays — the OTel reactor hook propagates the
     OTel context; this hook propagates MDC and is complementary),
   - keeps Grafana dashboards (`gateway-red`, `gateway-mesh`),
3. **Brave / Zipkin dependencies are removed** from the observability pom.
4. Micrometer tracing bridges to OTel, not Brave.

## Step-by-step recipe

### 1. Add `gateway-core/otel` as a dependency

In `gateway-core/observability/pom.xml`, add:

```xml
<dependency>
  <groupId>io.telekom.gateway</groupId>
  <artifactId>otel</artifactId>
  <version>${project.version}</version>
</dependency>
```

and remove:

```xml
<!-- DELETE -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
  <groupId>io.zipkin.reporter2</groupId>
  <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>  <!-- now transitive via otel module -->
</dependency>
```

Add the Micrometer → OTel bridge:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

### 2. Deprecate `TracingCustomizer`

Rename `TracingCustomizer` to `LegacyBraveTracingCustomizer` and add
`@Deprecated(since = "…", forRemoval = true)` plus
`@ConditionalOnProperty(prefix = "gateway.observability.tracing", name = "brave-legacy", havingValue = "true")`
so it only activates when someone explicitly opts in.

The class no longer needs to register a Zipkin `AsyncReporter` or a Brave
propagator — those concerns moved to `gateway-core/otel`:

- **Propagators** — already pinned to `tracecontext,baggage,b3multi` by
  `OtelPropagatorsConfig`. Jumper B3 interop stays.
- **Exporter** — OTLP is the default. Zipkin-format export is not needed by
  gateway-core peers; deprecate and remove the Zipkin reporter.

### 3. Redaction as an OTel `SpanProcessor`

`SecretRedactor` today is called from the Brave span name customiser. Port it:

```java
@Component
public class RedactingSpanProcessor implements SpanProcessor {
  private final SecretRedactor redactor;

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    span.updateName(redactor.redactUri(span.getName()));
    // copy-redact http.url, http.target, db.statement, etc.
  }

  @Override public boolean isStartRequired() { return true; }
  @Override public void onEnd(ReadableSpan s) {}
  @Override public boolean isEndRequired() { return false; }
}
```

Register it via the `AutoConfiguredOpenTelemetrySdk.addTracerProviderCustomizer`
hook the OTel module exposes — the observability module should add a
`@Bean BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>`
that calls `builder.addSpanProcessor(new RedactingSpanProcessor(redactor))`.
**Note:** this requires a small addition to `OtelAutoConfiguration` (expose a
tracer-provider customiser collection); track that as a prerequisite PR.

### 4. Micrometer tracing → OTel bridge

Micrometer's `Tracer` façade stays — developers still call `Tracer#nextSpan()`.
Swap the implementation from Brave to OTel by replacing
`micrometer-tracing-bridge-brave` with `micrometer-tracing-bridge-otel`
(see step 1). No call-site changes needed.

### 5. Remove `otlp` Spring profile from `application.yml`

With OTLP now the default exporter, delete the `otlp` profile and move any
profile-gated keys to the unconditional tree. The observability module's
`application.yml` should only contain **module-specific** keys now — redaction
filter list, RED metric names, zone labels — not SDK wiring.

### 6. Verify the migration

After applying the above, run:

```bash
./mvnw -pl gateway-core/observability verify
```

The existing Cucumber feature tests (`metrics.feature`, `tracing.feature`,
`logging.feature`) should pass unchanged — metrics still go through the
Prometheus scrape, traces still include B3 and W3C context, logging still uses
the structured encoder. The only visible difference is that `/actuator/metrics`
now lists `otlp` as a meter registry alongside `prometheus`, and OTLP spans
land at the collector instead of at Zipkin.

### 7. Drop `docker-compose` Zipkin (if still present)

If `docker-compose.yml` exposes Zipkin ports (`9411`), they can be removed once
no peer in the gateway-core landscape consumes Zipkin format. Jaeger v2 already
accepts OTLP on 4317/4318; the collector (see `docker-support/otel/`) fronts
that now.

---

## Rollback

Set `gateway.observability.tracing.brave-legacy=true` and export
`OTEL_TRACES_EXPORTER=none` — Brave reactivates and OTLP goes silent. The
migration is therefore feature-flag-gated end to end.
