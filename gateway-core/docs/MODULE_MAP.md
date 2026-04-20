<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# gateway-core module map

`gateway-core` is a multi-module Maven reactor of 20 sibling modules. This document gives
one paragraph per module: what it owns, what it depends on, and where its logic comes
from (ported from jumper, new for `gateway-core`, or third-party wrapping). For the
architectural context, see [../ARCHITECTURE.md](../ARCHITECTURE.md). For the rationale
behind the split, see [adr/ADR-001-kong-free-architecture.md](adr/ADR-001-kong-free-architecture.md)
and [adr/ADR-002-crd-control-plane.md](adr/ADR-002-crd-control-plane.md).

## Data plane

### 1. `core-gateway`

The data-plane application module. Boots Spring Cloud Gateway on Netty, wires the
reactive route locator from `core-routing`, and mounts the filter factories from
`core-filters`, `core-inbound-auth`, `core-rate-limit`, and `core-cors`. Owns
`Application.java` equivalent (ported from `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/Application.java`),
the health endpoint, and the bootstrap-config schema
(`zone`, `controller.address`, `redis.url`). No business logic lives here; it is
integration only.

### 2. `core-filters`

The canonical home for request/response filters. Ports `RequestFilter`,
`UpstreamOAuthFilter`, `RemoveRequestHeaderFilter`, `ResponseFilter`,
`RequestTransformationFilter`, `ResponseTransformationFilter`, and the body-rewrite
helpers from
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/filter/`. Adds new
filters for request validation (size, content-type, optional JSON schema) and
Resilience4j circuit breakers. Exposes the plugin SPI: any bean extending
`AbstractGatewayFilterFactory` on the classpath is discoverable by `core-routing`.

### 3. `core-routing`

Dynamic route matching and hot-reload. Owns the `RouteLocator` implementation that is
fed by snapshots pushed from the controller over gRPC. Performs path / method / host /
header matching (the job Kong used to do; see
[adr/ADR-001-kong-free-architecture.md](adr/ADR-001-kong-free-architecture.md) gap #4).
Performs active upstream health-checks per backend (instance-level), distinct from the
zone-level health in `core-zone-health`. Supports weighted routing and traffic splits
for canaries.

### 4. `core-inbound-auth`

Inbound authentication and consumer-identity resolution. Validates client JWTs using
Spring Security's `ReactiveJwtDecoder` with a cached per-realm JWKS. Resolves
`GatewayConsumer` + `GatewayCredential` from the pushed snapshot and emits the
`x-consumer-id`, `x-consumer-custom-id`, `x-consumer-groups`, `x-consumer-username`
headers (the same set Kong used to emit and that jumper strips in
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/RoutingConfiguration.java:52-57`).
Also supports API-key authentication.

### 5. `core-rate-limit`

Rate limiting via Redis-backed token bucket (Lettuce client). Supports per-consumer,
per-route, and global policies defined in `GatewayPolicy.rateLimits`. Emits
`X-RateLimit-*` response headers and Micrometer counters. Reuses the Redis client
`core-zone-health` already maintains, so the data plane opens only one Redis connection
pool.

### 6. `core-cors`

CORS preflight handling and header management. Thin wrapper over Spring Cloud Gateway's
built-in CORS filter with a policy object driven from `GatewayPolicy.cors`. Supports
per-route overrides and safe defaults (deny-by-default, explicit origins, short
`max-age`).

## Token & mesh

### 7. `core-token`

All token-related logic: minting RS256 JWTs, fetching OAuth client-credential tokens,
caching. Direct port of
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/TokenGeneratorService.java`,
`TokenFetchService.java`, `TokenCacheService.java`, and `KeyInfoService.java`, plus the
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/util/OauthTokenUtil.java`
and `RsaUtils.java` helpers. Caffeine is the cache backend. Supports the five token
scenarios documented in jumper's README: one-token, LMS legacy, mesh, external OAuth,
basic, x-token-exchange.

### 8. `core-mesh`

Mesh orchestration glue. Selects the right token scenario per route, parses and honours
the `jumper_config` header during migration (ADR-004), handles failover-target selection
(ported from
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/JumperConfigService.java`),
and owns the mesh-specific request mutations (`Consumer-Token` header,
`X-Spacegate-Token`, `X-Origin-Stargate`, `X-Origin-Zone`). See
[adr/ADR-003-zone-jwt-mesh.md](adr/ADR-003-zone-jwt-mesh.md) for the wire contract this
module preserves.

### 9. `core-zone-health`

Zone-health cache and Redis pub/sub. Direct port of
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/ZoneHealthCheckService.java`
and `RedisZoneHealthStatusService.java`, keeping the `zone.health.status` Micrometer
gauge (`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/ZoneHealthCheckService.java:43-56`)
and the `RedisHealthCheck` job
(`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/job/RedisHealthCheck.java`).
Adds an optional publisher role so `gateway-core` itself can emit zone-health messages
instead of relying on an external job.

## Control plane

### 10. `core-crd-api`

The Kubernetes CRD definitions and their generated Java POJOs. Houses the OpenAPI
schemas for `GatewayRoute`, `GatewayConsumer`, `GatewayCredential`, `GatewayZone`,
`GatewayMeshPeer`, and `GatewayPolicy` (see
[adr/ADR-002-crd-control-plane.md](adr/ADR-002-crd-control-plane.md) for their design).
Also owns the snapshot protobuf schema used by the gRPC push channel between controller
and data plane. Pure code-gen + tests; no runtime.

### 11. `core-controller`

The reconciler. A Spring Boot app using the fabric8 kubernetes-client informer
framework to watch CRDs, resolve cross-references, build an immutable `Snapshot`, and
push deltas to data-plane pods over gRPC. Leader-elected via Kubernetes `Lease`. Updates
CRD status subresources with `observedGeneration` and `acceptedReplicas`.

### 12. `core-admission`

The validating admission webhook. Ships in the controller image but is a separate
module so its logic (schema cross-field invariants, reference checks, policy safety)
can be unit-tested without spinning a controller. Rejects bad applies at the Kubernetes
API boundary.

### 13. `core-config-store`

In-memory snapshot store and diff engine. Accepts `Snapshot` objects from `core-controller`,
computes SHA-256 content addresses, stores the last N versions, and emits deltas on
request. Shared between the controller (server side) and the data-plane's gRPC client
(which keeps the last-known-good snapshot durable on local disk for restart-time resilience).

## Cross-cutting

### 14. `core-observability`

Metrics, structured logging, health endpoints. Ports `NettyMetricsConfig`
(`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/NettyMetricsConfig.java`,
from PR #102 / commit b3b176f), wires Micrometer to Prometheus, standardizes log
correlation via MDC, and exposes the `/actuator/health` + `/actuator/metrics` endpoints.
Defines the metrics taxonomy (`gateway.request.*`, `gateway.token.*`, `zone.health.status`)
so every module reports consistently.

### 15. `core-tls`

Shared TLS configuration. Ports `TlsHardeningConfiguration` and `WarningTrustManager`
from `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/`.
Configures both the inbound Netty listener and the outbound `WebClient` so cipher /
protocol posture is consistent.

### 16. `core-tracing`

Tracing plumbing. Ports `TracingConfiguration` and
`CloudGatewayPrefixedGatewayObservationConvention` from
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/`. Supports B3
Zipkin propagation (jumper README line 330) and OpenTelemetry export; propagates trace
context between controller and data plane over the gRPC channel too.

### 17. `core-audit`

Structured audit logging. Ports
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/AuditLogService.java`
and the `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/model/config/AuditLog.java`
record. Extends the scope to cover control-plane events (CRD apply, admission decision,
snapshot push, snapshot ack) on top of the existing data-plane events (request
forwarded, token minted, failover triggered).

## Tooling & integration

### 18. `core-migration-tool`

The Kong + jumper -> CRD converter. Standalone CLI detailed in
[adr/ADR-004-migration-path.md](adr/ADR-004-migration-path.md). Reads Kong declarative
YAML, jumper `application.yml`, and the zone's Helm values; emits a complete set of
`GatewayRoute`, `GatewayConsumer`, `GatewayCredential`, `GatewayZone`, `GatewayMeshPeer`,
and `GatewayPolicy` manifests plus a migration report. Idempotent. Retired once fleet
migration completes.

### 19. `core-testkit`

Shared test utilities: Testcontainers helpers for Redis, Kubernetes (`k3s`), and IdPs;
JWT builders; traffic-replay harness that consumes captured Kong traffic and diffs it
against `gateway-core`. Supports the shadow-traffic phase in ADR-004 and is the
backbone of our CI suite. Not packaged in production images.

### 20. `core-bom`

Maven bill-of-materials. Pins versions for every direct and transitive dependency
(Spring Boot, Spring Cloud, Lettuce, fabric8, Resilience4j, Caffeine, Nimbus JOSE+JWT,
gRPC, protobuf). Downstream consumers depend on `core-bom` to inherit consistent
versions. Owns the project's supported-Java-version policy (currently Java 21).
