<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core observability — OpenTelemetry first

**OpenTelemetry is the primary observability backbone for gateway-core.** All
three signals — traces, metrics, and logs — flow through the OTel SDK and
leave each service over OTLP. A Micrometer → OTLP bridge covers existing
meters, and the Prometheus scrape endpoint stays on for backwards compatibility
with the dashboards and recording rules we already run.

This document is the landscape-level overview. Per-module specifics live in
each module's `README.md` — most importantly
[`gateway-core/otel/README.md`](../otel/README.md) for SDK wiring and
[`gateway-core/otel/docs/MIGRATION.md`](../otel/docs/MIGRATION.md) for the
Brave → OTel recipe the observability module needs to apply.

---

## The three signals

### Traces

| Concern              | Choice                                                         |
|----------------------|----------------------------------------------------------------|
| SDK                  | `io.opentelemetry:opentelemetry-sdk` 1.44.x                    |
| Exporter             | OTLP gRPC to the local collector (`http://localhost:4319`)     |
| Sampler              | `parentbased_always_on` (dev) / `parentbased_traceidratio`     |
| Propagators          | `tracecontext, baggage, b3multi`                               |
| Span naming          | Redacted via the observability module's `SecretRedactor`        |
| Reactor context flow | Installed by `io.telekom.gateway.otel.ReactorOtelContext`      |

The **B3 ↔ W3C bridge** is the load-bearing piece for the migration window:
jumper-era services upstream of gateway-core still emit B3 multi-headers.
The composite propagator in `OtelPropagatorsConfig` extracts B3 on ingress and
injects both W3C TraceContext **and** B3 on egress. That means:

- a jumper caller → gateway-core hop keeps the same trace id (jumper's B3
  propagates, the OTel SDK reads it),
- a gateway-core → modern peer hop carries W3C `traceparent`,
- a gateway-core → jumper peer hop still carries B3.

No service needs to know about the other format's existence — the gateway is
the translator.

### Metrics

Two paths out of each service, both live in parallel:

1. **Micrometer → OTLP** — primary. `io.micrometer:micrometer-registry-otlp`
   pushes to the collector every 10s. Resource attributes (`service.namespace=gateway-core`,
   `gateway.zone`, `gateway.realm`) are attached by the OTel module.
2. **Prometheus scrape** — backwards compatibility. `/actuator/prometheus`
   stays exposed. The collector-scrape is there for the Grafana dashboards
   that read from Prometheus, not for long-term storage.

RED metrics (request count, error count, duration) come from the observability
module's `RedMetricsFilter` and automatically pick up the `otlp` registry via
Micrometer's composite.

### Logs

Structured JSON to stdout (Logback + Logstash encoder) stays as the canonical
log format for `kubectl logs`. On top of that, when
`gateway.otel.logs.enabled=true`, the module attaches an `OpenTelemetryAppender`
to the root logger that exports log records over OTLP — carrying the current
span's `trace_id` and `span_id` automatically so log-trace correlation works
without manual MDC copy.

---

## How the collector is configured

Config lives in [`docker-support/otel/collector.yaml`](../../docker-support/otel/collector.yaml)
and is mounted into the `otel-collector` service in `docker-compose.yml`.

### Local dev topology

```
       host:4319 (gRPC) ┐
                        ├─→  otel-collector  ─┬─→ jaeger   (traces)
       host:4320 (HTTP) ┘                     ├─→ debug    (metrics, stdout)
                                              └─→ debug    (logs, stdout)
                        host:9090 ─→  prometheus (scrapes gateway-core pods)
```

- Jaeger UI: <http://localhost:16686>
- Collector health: <http://localhost:13133>
- Prometheus UI: <http://localhost:9090>

Jaeger no longer exposes 4317/4318 on the host — the collector owns OTLP
ingress for the landscape. That keeps one ingress path for every signal,
makes redaction / enrichment centrally configurable, and leaves a single
place to swap in auth when we connect to a prod backend.

### Pointing at a production collector

Set a single env var on each deployed service:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.your-backend.example
```

Add an auth header if the backend requires one (never commit the value):

```bash
OTEL_EXPORTER_OTLP_HEADERS=authorization=<otlp-header-value>
```

Examples (set the value in a k8s secret, reference via `envFrom`):

- **Honeycomb** — `OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io:443`,
  header `x-honeycomb-team=<otlp-header-value>`.
- **Grafana Tempo / Alloy** — `OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.grafana.net`,
  header `authorization=Basic <otlp-header-value>`.
- **Datadog OTLP intake** — `OTEL_EXPORTER_OTLP_ENDPOINT=https://api.datadoghq.eu`,
  header `dd-api-key=<otlp-header-value>`.

For **on-cluster** deploys, prefer running a collector sidecar or daemonset
and pointing services at it (`http://otel-collector.observability:4317`).
That keeps deployment configs identical between dev and prod — only the
collector's exporter config changes.

---

## B3 ↔ W3C interop summary

| Scenario                                | Inbound header              | Outbound header(s)                  |
|-----------------------------------------|-----------------------------|-------------------------------------|
| Jumper → gateway-core                   | `X-B3-TraceId` + sibs       | `traceparent`, `X-B3-TraceId` + sibs|
| Modern service → gateway-core           | `traceparent`               | `traceparent`, `X-B3-TraceId` + sibs|
| gateway-core → modern service           | —                           | `traceparent`                       |
| gateway-core → jumper                   | —                           | `traceparent`, `X-B3-TraceId` + sibs|
| Baggage across any hop                  | `baggage`                   | `baggage`                           |

The composite propagator extracts whichever inbound format is present (last
writer wins on the same trace id) and injects both on egress, so downstream
peers get the format they prefer.

---

## Further reading

- [`gateway-core/otel/README.md`](../otel/README.md) — module contract, env vars, sampling.
- [`gateway-core/otel/docs/MIGRATION.md`](../otel/docs/MIGRATION.md) — how the
  observability module migrates off Brave.
- [`docker-support/otel/collector.yaml`](../../docker-support/otel/collector.yaml) — local collector config.
