<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: CC0-1.0 -->

# gateway-core module map

This document is the canonical one-paragraph-per-module guide to `gateway-core`.
Modules are listed here exactly as they appear in the filesystem (see `gateway-core/`
on `main`).

For the architectural context, see [../ARCHITECTURE.md](../ARCHITECTURE.md). For the
rationale behind the split, see [adr/ADR-001-kong-free-architecture.md](adr/ADR-001-kong-free-architecture.md)
and [adr/ADR-002-crd-control-plane.md](adr/ADR-002-crd-control-plane.md). For known gaps
across these modules, see [CRITIQUE.md](CRITIQUE.md).

> **Naming note.** Maven artifact IDs and directory names use `kebab-case`;
> Java packages convert hyphens to underscores (e.g. `gateway-core/rate-limiter/`
> → `io.telekom.gateway.rate_limiter`). Each module is currently a *standalone*
> Spring Boot project — there is no aggregating reactor pom yet, which is
> tracked as a separate follow-up.

## Control plane

### `api-crds`

Kubernetes Custom Resource Definitions for the six resources the controller reconciles:
`GatewayRoute`, `GatewayConsumer`, `GatewayCredential`, `GatewayZone`,
`GatewayMeshPeer`, `GatewayPolicy`. Ships OpenAPI v3 schemas, printer columns, and CEL
validation rules. Packaged as a pom-only module whose `verify` step runs
`kubectl kustomize` and (optionally, with `-Pwith-cluster`) `kubectl apply
--dry-run=client`. No Java.

### `controller`

Spring Boot + fabric8 control-plane app. Watches the six CRDs via SharedIndexInformers,
aggregates their state into an immutable zone-scoped snapshot via `SnapshotBuilder`, and
pushes to the data-plane pods through `DataPlanePushService`. **Leader election is not
yet implemented**; the helm chart pins this at `replicaCount: 1` (CRITIQUE F-008).

### `admin-status-api`

Reactive read-only HTTP API on port 8091 exposing `/admin/routes`, `/admin/zones`,
`/admin/consumers`, `/admin/cache-stats`, `/admin/snapshot` plus Swagger UI at
`/admin/docs`. Consumers override `RuntimeStateReader` to plug in the live runtime view;
tests use the in-memory default. `/admin/**` is protected by basic auth or mTLS via
`admin.security.mode`. OpenAPI 3.1 spec shipped at
`src/main/resources/openapi/admin-api.yaml`.

### `migration-tool`

Picocli CLI `gateway-migrate` with subcommands `migrate` / `diff` / `validate`. Reads
Kong decK YAML and emits gateway-core CRDs (`GatewayRoute`, `GatewayConsumer`,
`GatewayCredential` + companion `Secret`, `GatewayPolicy`). Kong plugins without a
mapping (`jwt`, `key-auth`, `prometheus`, `bot-detection`, …) land in
`UnmigratedReport` with exit code 2 so `diff` surfaces them to operators.

## Data plane

### `proxy`

Greenfield Spring Cloud Gateway WebFlux proxy. Defines the canonical
`FilterPipelineStage` enum (stages 100-900) and the `PipelineOrderedFilter` base class
that sibling modules extend. The module currently ships as a skeleton — the routing
config, admin config-snapshot receiver, and wiring to the sibling filter modules are
not yet assembled. Running it standalone gives you an empty Spring Boot app; the
"deployable proxy" assembly PR is the missing integration step.

### `auth-inbound`

Inbound identity resolution. `InboundAuthenticator` with four implementations: JWT
(Spring Security `ReactiveJwtDecoder`, JWKS-cached), API-key (configurable header or
query parameter, constant-time store compare), RFC 7617 Basic, RFC 7662 opaque-token
introspection. `InboundAuthFilter` at pipeline order 200 dispatches by
`AuthContext.Type`, publishes the `AuthContext` to exchange attributes, returns 401 on
failure. Constant-time store: `InMemoryCredentialStore` uses SHA-256 digests and
`MessageDigest.isEqual`.

### `auth-outbound`

Outbound credential minting — the five jumper-era scenarios. Strategies: `OneTokenStrategy`
(RS256 JWT with claim injection), `MeshTokenStrategy` (peer-zone client-credentials +
`X-Consumer-Token` propagation), `ExternalOAuthStrategy` (standard client-credentials),
`BasicAuthStrategy`, `TokenExchangeStrategy` (pass-through `X-Token-Exchange`).
`TieredTokenCache` = Caffeine + single-flight dedupe + 10 s expiry buffer. Strategies
publish the outbound `Authorization` value to an exchange attribute; the proxy
forwarder applies it just before upstream dispatch. `OutboundAuthFilter` at pipeline
order 600.

### `rate-limiter`

Redis-backed sliding-window limiter. Correctness claim: the limit holds **in aggregate
across all replicas** via a single atomic Lua script (`EVAL`/`EVALSHA`). API records
`RateLimitKey` / `RateLimitPolicy` / `RateLimitDecision`; filter at order 300 emits
`X-RateLimit-Limit` / `-Remaining` / `-Reset` on every response and `429` +
`Retry-After` on deny. Fail-open on Redis outage (configurable). Proven by a multi-pod
Testcontainers integration test.

### `mesh-federation`

Zone / realm federation ported from jumper. `ZoneHealthRegistry` with per-zone +
summary Micrometer gauges, `RedisZoneHealthPubSub` (heartbeat + stale-timeout
reconciliation on channel `gateway-zone-status`), `FailoverSelector` (pure function),
`MeshPeerRegistry`, and `MeshFederationFilter` at order 800 that honours
`X-Failover-Skip-Zone`.

### `request-validation`

First stage in the pipeline (orders 100-140). `CorsPolicy` / `ValidationPolicy` records
+ four filters: `CorsFilter` (preflight + response decoration), `ContentTypeFilter`
(415 on mismatch), `SizeLimitFilter` (Content-Length fast path + streaming guard,
releases buffers on overflow), `JsonSchemaFilter` (Caffeine-cached compiled schemas,
problem+json with JSON Pointer violations).

### `service-discovery`

`ServiceResolver` SPI with k8s `EndpointSlice` watcher, static DNS, and optional
Consul. `CompositeResolver` dispatches by URI scheme (`k8s://` / `dns://` / `consul://`).
`WeightedRoundRobin` ports jumper's load-balancing choice. Filter at order 500 rewrites
`GATEWAY_REQUEST_URL_ATTR` to a concrete endpoint.

### `policy-engine`

Authorization / policy evaluation. `SpelPolicyEvaluator` (default, Caffeine-cached
parsed expressions) and `RegoPolicyEvaluator` (optional, behind
`gateway.policy.rego.enabled`). Filter at order 400 returns 403 + `X-Policy-Reason` on
deny; `add_header:*` and `log` obligations on allow. CRD enum aligned with
implementation: `engine: [spel, rego]` (CRITIQUE F-015).

### `circuit-breaker`

Resilience4j integration. `ResilienceRegistry` caches per-route `CircuitBreaker` +
`Bulkhead` instances with tagged Micrometer metrics. `ResilienceFilter` at order 700
wraps the chain with `Bulkhead → CircuitBreaker → Retry.backoff → chain.filter`.
Mappings: `CallNotPermitted` → 503, `BulkheadFull` → 429, retries-exhausted → 502.
Non-idempotent methods skip retry.

### `plugin-spi`

`GatewayPlugin` ServiceLoader SPI (Spring-free API). Hot-reload via `PluginLoader` with
a daemon `WatchService` — generations reload atomically: new classloader built +
registry swapped first, **then** previous classloaders closed (ordering from
CRITIQUE F-016). Ships an `XRequestIdPlugin` example.

## Cross-cutting

### `observability`

RED metrics (per-route counters + timers), Micrometer Prometheus registry, B3 tracing
via Brave bridge, optional OTLP exporter (profile `otlp`), structured JSON logging via
`LogstashEncoder`, reactor MDC propagation, Grafana dashboards
(`gateway-red.json` / `gateway-mesh.json`). `SecretRedactor` scrubs sensitive URL
parameters + headers before they land in spans; redaction list covers OAuth2 flow
params (`access_token`, `code`, `state`, `token`, `jwt`) and headers used by the mesh
flow (`X-Consumer-Token`, `X-API-Key`, `X-Auth-Token`).

### `otel`

Separate OpenTelemetry module that auto-configures an `OpenTelemetrySdk` bean with
composite propagation (`tracecontext,baggage,b3multi` for jumper-era interop), OTLP
exporter via env (`OTEL_EXPORTER_OTLP_ENDPOINT`), reactor context threading, and an
optional Logback bridge. Ships a docker-compose `otel-collector` service and
`docs/MIGRATION.md` describing how the observability module should transition off
Brave onto OTel when ready.

## Tooling

### `cli`

Picocli `gatectl` CLI for operators: `get-routes`, `get-zones`, `get-consumers`,
`get-mesh-peers`, `describe-route|zone|consumer`, `logs`, `health`. Table / JSON /
YAML output, kubeconfig context + namespace overrides. Exit codes: 0 success / 1
command / 2 usage. Tests currently blocked on a Jackson 2.21 ↔ fabric8 6.13.5
incompatibility in the mock-server path; main sources compile cleanly.

### `testkit-hydra`

Ory Hydra integration for realistic OIDC tests. Adds `hydra-db` + `hydra-migrate` +
`hydra` + `hydra-seed` to `docker-compose.yml`; the seed script generates random
client secrets (`openssl rand`) for two clients (`client_credentials` and
`authorization_code`) and writes them to a mode-600 `/secrets/hydra-clients.json`. The
`HydraClient` + `HydraFixture` helpers let Cucumber tests pull a real JWT without
baking any secret into the repo.

### `helm-charts`

Two charts — `control-plane` and `data-plane` — plus an umbrella
`examples/two-zone-mesh/` that wires two zones together with a bundled Redis subchart
and demo CRs. `verify` runs `helm lint` + `helm template` + optional
`kubectl --dry-run=client` against the rendered manifests.

### `docker`

Distroless multi-stage Dockerfiles for `controller` and `proxy` on
`gcr.io/distroless/java21-debian12:nonroot`. Healthchecks set to `NONE` (no shell in
distroless) — Kubernetes probes handle liveness/readiness instead.

### `load-test-suite`

Load driver built on reactor-netty + HdrHistogram with lightweight dummy upstreams
(`FastUpstream`, `SlowUpstream`, `FlakyUpstream`). Scenarios: steady-state, slow-upstream
connection-growth (proves Pearson r between upstream latency and gateway open-connection
demand), spike recovery, backpressure, resilience-under-load.

### `e2e-test-suite`

Cross-zone Cucumber suite using Testcontainers: 1 Redis + 3 MockServer upstreams + 3
embedded gateway-core zones. Features cover the mesh-JWT claim flow
(`originZone`/`originStargate`/audience), zone-health-aware failover, and
token-caching single-flight (200 concurrent requests ⇒ 1 peer-token mint).

## Known gaps across the landscape

See [CRITIQUE.md](CRITIQUE.md). The largest missing piece is integration: no
aggregating reactor pom exists, and no module depends on another — so `proxy` does not
yet compose the sibling filter modules into a runnable data plane. That integration PR
is the gating item for any end-to-end perf / ops work.
