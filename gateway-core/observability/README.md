<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: observability

Drop-in module for the Kong-free `gateway-core` runtime that lifts and extends
the Micrometer / Prometheus / B3 / OTLP / Logstash stack pioneered by `jumper`.

It is a standalone Spring Boot 3.5.13 WebFlux module. Sibling modules either
depend on it directly (recommended) or simply include the produced JAR on their
classpath — Spring Boot auto-configuration wires everything via
`io.telekom.gateway.observability.ObservabilityAutoConfiguration`.

## Metric inventory

| Metric                         | Type    | Tags                                  | Notes                              |
| ------------------------------ | ------- | ------------------------------------- | ---------------------------------- |
| `gateway.requests`             | counter | `route`, `method`, `status`, `zone`   | Per-request counter (RED: Rate)    |
| `gateway.request.duration`     | timer   | `route`, `method`, `status`, `zone`   | End-to-end duration with histogram |
| `http.server.requests`         | timer   | Spring defaults                       | Emitted by Spring WebFlux          |
| `http.client.requests`         | timer   | Spring defaults                       | Emitted by Spring WebFlux client   |
| `reactor.netty.*`              | various | `uri` scoped to `/gateway`            | Netty server + client pools        |

The `gateway.request.duration` histogram is published with percentiles so
Grafana can render p50/p95/p99 without client-side aggregation.

### Redaction rules

`SecretRedactor` scrubs both URL query parameters and header values:

- **Query params** (pattern match, defaults): `X-Amz-.*`, `sig`, `signature`,
  `access_token`, `refresh_token`, `id_token`, `password`, `client_secret`,
  `api[_-]?key`. Extend via
  `gateway.observability.tracing.filter-param-list`.
- **Headers** (exact, case-insensitive): `authorization`,
  `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key`,
  `x-amz-security-token`. Values are replaced with `[redacted]`.

If a URL cannot be parsed, the whole query string is dropped — failing closed.

## Dashboards

Two ready-to-import Grafana dashboards live under
`src/main/resources/grafana/dashboards/`:

- `gateway-red.json` — RED view per route (rate, error ratio, p95/p99
  duration, status breakdown). Variable: `zone`.
- `gateway-mesh.json` — zone health matrix: traffic, error ratio, cross-zone
  latency heatmap, availability, active routes.

Both dashboards assume a Prometheus datasource named `DS_PROMETHEUS`.

```bash
curl -X POST http://localhost:3000/api/dashboards/db \
  -H 'Content-Type: application/json' \
  -d @src/main/resources/grafana/dashboards/gateway-red.json
```

## How sibling modules attach tags

`RedMetricsFilter` determines `zone` from
`gateway.observability.zone` (fallback: `gateway.zone.name`, then `default`).
Extra tags can be attached by publishing a `MeterFilter` bean:

```java
@Bean
MeterFilter myModuleTagContributor() {
  return MeterFilter.commonTags(Tags.of("team", "platform"));
}
```

Modules that need their own RED metrics per concept (e.g. per-tenant) should
*not* add tags to `gateway.requests` (cardinality). Instead they publish a
sibling metric keyed to the concept and reuse the zone tag from Micrometer's
common tags.

## Tracing propagation

- **Incoming**: `B3_MULTI` (header per field) is the default. Single-header B3
  is accepted because Brave decodes both.
- **Outgoing**: propagated automatically by
  `spring-cloud-starter-gateway-server-webflux`.
- **OTLP export**: activated with Spring profile `otlp` *or*
  `gateway.observability.tracing.otlp.enabled=true`.
- **Zipkin**: always enabled (default to `http://localhost:9411`) to keep
  parity with jumper and Jaeger's Zipkin receiver.

Span-name customisation is in `TracingCustomizer`. The URI attached to
`spring.cloud.gateway` spans is redacted through `SecretRedactor`.

## Structured logging

`StructuredLogbackConfig` installs a `LogstashEncoder` as the sole console
appender at startup (toggle with
`gateway.observability.logging.json-enabled=false`). The encoder includes MDC
and ships per-line JSON — ready for ELK / Loki / any log router.

`ReactorMdcPropagation` registers:

1. Hooks on every reactor operator to re-populate SLF4J's MDC from the reactor
   context on each signal.
2. `ContextRegistry` `ThreadLocalAccessor`s for `traceId`, `spanId`, `route`,
   `zone` so Spring Boot's automatic context propagation
   (`spring.reactor.context-propagation=auto`) picks up the keys.

Typical module usage:

```java
return Mono.fromCallable(this::work)
    .contextWrite(ReactorMdcPropagation.writeMdcToContext());
```

## Running locally / E2E

From this module:

```bash
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis \
    redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```

## Testing

Cucumber specs under `src/test/resources/features` cover:

- `metrics.feature` — counter + timer increments on real HTTP traffic;
  `/actuator/prometheus` exposure.
- `tracing.feature` — B3 propagation end-to-end; query-param / header
  redaction.
- `logging.feature` — MDC preserved across reactor scheduler hops; JSON
  encoder emits expected fields.

Step glue is under `io.telekom.gateway.observability.steps`.
