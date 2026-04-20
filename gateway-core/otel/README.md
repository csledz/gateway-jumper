<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: otel

First-class OpenTelemetry wiring for the **gateway-core** landscape. Drop this
module onto the classpath of any sibling data-plane or control-plane module and
you get a consistent OTel stack: traces, metrics, logs — all exported over OTLP
to a collector of your choice, with B3 ↔ W3C propagator interop preserved for
jumper-era peers and a Micrometer → OTLP bridge for existing meters.

Prometheus scrape remains enabled by default so the `docker-compose prometheus`
target and any existing dashboards keep working.

---

## Depending on the module

```xml
<dependency>
  <groupId>io.telekom.gateway</groupId>
  <artifactId>otel</artifactId>
  <version>${gateway-core.version}</version>
</dependency>
```

The module ships a Spring Boot auto-configuration
(`io.telekom.gateway.otel.OtelAutoConfiguration`) and a default
`application.yml`. Nothing else has to be wired — the SDK is built once,
resource attributes are injected, propagators are pinned, and the reactor hook
is installed.

## Environment variable contract

Everything that could be secret is sourced from env. No literal tokens, no
hardcoded URLs.

| Variable                       | Purpose                                                 | Default                 |
|--------------------------------|---------------------------------------------------------|-------------------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT`  | OTLP gRPC endpoint for traces + metrics + logs          | `http://localhost:4317` |
| `OTEL_EXPORTER_OTLP_PROTOCOL`  | `grpc` or `http/protobuf`                               | `grpc`                  |
| `OTEL_EXPORTER_OTLP_HEADERS`   | Comma-separated `key=<otlp-header-value>` pairs         | *unset*                 |
| `OTEL_SERVICE_NAME`            | Overrides `spring.application.name`                     | *unset*                 |
| `OTEL_RESOURCE_ATTRIBUTES`     | Extra static resource attrs                             | *unset*                 |
| `OTEL_TRACES_SAMPLER`          | Sampler (`parentbased_always_on`, `traceidratio`, …)    | `parentbased_always_on` |
| `OTEL_METRIC_EXPORT_INTERVAL`  | Metric push interval, ms                                | `10000`                 |
| `HOSTNAME`                     | Pod name, used for `service.instance.id`                | *process UUID fallback* |
| `gateway.zone.name`            | Zone tag (`gateway.zone` resource attr)                 | `default`               |
| `gateway.realm.name`           | Tenancy tag (`gateway.realm` resource attr)             | `default`               |
| `gateway.otel.enabled`         | Kill-switch for the whole module                        | `true`                  |
| `gateway.otel.logs.enabled`    | Enables the Logback → OTel logs bridge                  | `false`                 |

> If the collector your deployment pipeline hands you demands authentication,
> set `OTEL_EXPORTER_OTLP_HEADERS=authorization=<otlp-header-value>` in the
> deployment secret — never commit it.

## Switching to a hosted collector

The module is agnostic to where OTLP lands. Point it at Honeycomb, Tempo,
Datadog, New Relic, Grafana Cloud — anything that speaks OTLP.

```bash
# Honeycomb (gRPC)
export OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io:443
export OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=<otlp-header-value>"

# Grafana Tempo via Alloy
export OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.com
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

For local development, leave the defaults: the `docker-support/otel/collector.yaml`
bundled in this repo runs a contrib collector on `4319` / `4320`, pipes traces
to Jaeger, and prints metrics via the debug exporter.

## Sampling guidance

- **Dev / local:** `parentbased_always_on` — keep everything.
- **Staging:** `parentbased_traceidratio` at `0.1` – `0.25` — enough to spot
  regressions, cheap to run.
- **Prod, high-volume gateways:** `parentbased_traceidratio` at `0.01` – `0.05`
  plus a tail-based sampler in the collector (errors always sampled).
- Always use a `parentbased_*` sampler so incoming decisions from jumper-era
  peers are respected.

## What this module does **not** do

- **Inject literal credentials.** Auth is env-only.
- **Configure the collector.** That lives in `docker-support/otel/collector.yaml`
  and in your Helm chart values.
- **Replace the observability module.** See [`docs/MIGRATION.md`](docs/MIGRATION.md)
  for how the sibling Brave-based observability module should transition.

## Build

```bash
./mvnw -pl gateway-core/otel verify
```

The Cucumber features under `src/test/resources/features/` assert against an
in-memory verification exporter; no Docker is required for the happy path. An
optional `@collector` scenario exercises a Testcontainers-backed collector for
pipeline-integration confidence — skipped automatically when Docker is absent.
