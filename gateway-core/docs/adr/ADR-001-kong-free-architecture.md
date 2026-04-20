<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# ADR-001: Kong-free gateway architecture

- **Status**: Accepted
- **Date**: 2026-04-20
- **Deciders**: Stargate platform engineering, Open Telekom Integration Platform
- **Related**: ADR-002 (CRD control plane), ADR-003 (zone/JWT mesh), ADR-004 (migration path)

## Context

The current Stargate edge runs **Kong Gateway** fronting a **gateway-jumper** sidecar.
Kong handles inbound concerns (TLS, route matching, inbound auth, rate limits, CORS,
consumer identity); jumper handles outbound concerns (OAuth token mint/exchange, mesh-JWT
handling between zones, zone-health-aware failover, upstream proxying). Communication
between Kong and jumper is header-based and stateless on the jumper side; the interface
is documented in jumper's README
(`/Users/A85894249/claude-code/gateway-jumper/README.md:13`) as "jumper is a sidecar of
Kong API Gateway".

Over time, the Kong layer has become the sharpest edge of operational pain:

- **Two runtimes per pod.** An OpenResty/Lua process plus a JVM means two GC/memory
  models, two log formats, two metric pipelines, two CVE streams.
- **Lua plugin SPI.** Custom behaviour requires Lua (or Go via go-plugin), which our team
  does not maintain in other products. The existing Kong plugins are a small, aging set
  and a continuous source of upgrade risk.
- **Declarative config via Kong's DB-less YAML** is imperfectly aligned with
  Kubernetes-native patterns; our operators already use the Gateway API style
  (`HTTPRoute`-like resources) in other products.
- **Kong adds its own header set** (`x-consumer-id`, `x-consumer-custom-id`,
  `x-consumer-groups`, `x-consumer-username`, `x-anonymous-consumer`, `x-anonymous-groups`,
  `x-forwarded-prefix`) which jumper has to strip before forwarding
  (`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/RoutingConfiguration.java:52-57`).
  This implicit contract has broken silently more than once on Kong upgrades.
- **Environment info is lost** between Kong and jumper. `SpectreRoutingFilter.java:37`
  explicitly notes that Kong does not forward the environment, so jumper reconstructs it
  by parsing the issuer from the incoming token. This kind of "recover-from-token" plumbing
  is exactly the integration tax we want to stop paying.
- **Kong is a latency tax.** Two proxies in sequence mean two TCP handshakes, two HPACK
  decodes, two filter chains. In our own benchmarks, collapsing to one pod consistently
  saves 2-4 ms at p99 even before optimization.

Jumper itself has matured well. The pieces we consider production-grade and worth lifting
verbatim are:

- `RequestFilter` + `UpstreamOAuthFilter` -- five token scenarios
  (one-token, LMS legacy, mesh, external OAuth, basic, x-token-exchange) implemented as
  a single coherent filter chain
  (`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/filter/`).
- `TokenGeneratorService` -- RS256 claim injection with full control over `kid`,
  `originZone`, `originStargate`, `env`, `requestPath`, `operation`, etc.
- `ZoneHealthCheckService` +
  `RedisZoneHealthStatusService` -- Redis pub/sub-based zone-health gossip with Micrometer
  gauges (`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/ZoneHealthCheckService.java`).
- `JumperConfigService` -- failover target selection from `jumper_config`.
- `TlsHardeningConfiguration` -- safe-by-default TLS cipher/protocol choices.
- `NettyMetricsConfig` -- Netty server metrics landed in PR #102 / commit b3b176f,
  currently the best-in-class piece of telemetry in either stack.

What Kong still does -- and nobody in jumper does -- is the gap we have to close in
`gateway-core`:

1. **Inbound authentication** (JWT validation against a realm's JWKS, API key auth).
2. **Rate limiting** (per consumer, per route, fixed window / sliding window).
3. **CORS** (preflight, header policy).
4. **Route matching** from externally declared routes (not a Spring-wired static list like
   jumper's `RoutingConfiguration`).
5. **Consumer identity resolution** (the `x-consumer-*` set that jumper currently strips).
6. **Dynamic route hot-reload** (no restart on route change).
7. **A control plane** (today: Kong admin API + CRD controller; tomorrow: our own CRD
   controller).
8. **Service discovery / health** beyond zone-level (instance-level upstream health).
9. **A plugin SPI** for custom behaviour.
10. **Request validation** (schema, size, content-type).
11. **Circuit breaker** for upstream failure isolation.
12. **A migration tool** to convert existing Kong declarative config + jumper
    `application.yml` to the new CRDs without operator-authored YAML rewrites.

## Decision

We will build `gateway-core`, a new greenfield gateway that **replaces Kong + jumper with
a single Spring Cloud Gateway-based JVM per pod**, driven by **Kubernetes CRDs** managed
by a **dedicated controller**. The decision has four parts:

### 1. Keep Spring Cloud Gateway as the data-plane runtime

Jumper is already SCG-based; SCG is Reactor/Netty under the hood and scales well for our
profile (many small requests, many concurrent connections, heavy TLS). We retain the
reactive model, the filter abstraction, and the Netty tuning we already validated.

### 2. Lift the mature parts of jumper into versioned modules

Rather than fork jumper, `gateway-core` includes new modules that hold the canonical copy
of the code moved over. Jumper will enter end-of-life once zone migration completes (see
ADR-004). The lifted pieces are:

| From jumper                               | To gateway-core module  |
|-------------------------------------------|--------------------------|
| `filter/RequestFilter.java`               | `core-filters`           |
| `filter/UpstreamOAuthFilter.java`         | `core-filters`           |
| `filter/RemoveRequestHeaderFilter.java`   | `core-filters`           |
| `filter/ResponseFilter.java`              | `core-filters`           |
| `service/TokenGeneratorService.java`      | `core-token`             |
| `service/TokenFetchService.java`          | `core-token`             |
| `service/TokenCacheService.java`          | `core-token`             |
| `service/ZoneHealthCheckService.java`     | `core-zone-health`       |
| `service/RedisZoneHealthStatusService.java` | `core-zone-health`     |
| `service/JumperConfigService.java`        | `core-mesh`              |
| `config/TlsHardeningConfiguration.java`   | `core-tls`               |
| `config/NettyMetricsConfig.java`          | `core-observability`     |
| `config/TracingConfiguration.java`        | `core-tracing`           |
| `service/AuditLogService.java`            | `core-audit`             |

The code comes with its existing tests and is re-verified in the new reactor. We are
paying down some technical debt in-flight (Lombok usage, package naming), but the core
logic is preserved.

### 3. Build the ex-Kong responsibilities as first-class Spring modules

| Gap                          | Module              | Implementation notes                                                   |
|------------------------------|---------------------|------------------------------------------------------------------------|
| Inbound auth                 | `core-inbound-auth` | Spring Security ReactiveJwtDecoder + cached JWKS per realm.            |
| Rate limiting                | `core-rate-limit`   | Redis-backed token bucket via Lettuce (reuses the Redis client that's already there for zone-health). |
| CORS                         | `core-cors`         | SCG's built-in CORS filter wrapped in a policy object driven by `GatewayPolicy`. |
| Route matching               | `core-routing`      | Dynamic `RouteLocator` fed by the controller snapshot.                 |
| Consumer identity            | `core-inbound-auth` | Resolves `GatewayConsumer` + `GatewayCredential`; emits the same `x-consumer-*` header contract jumper used to consume, so the in-zone upstream interface is unchanged. |
| Dynamic routes               | `core-routing`      | Atomic swap of the in-memory route table on snapshot push.             |
| Control plane                | `core-controller`   | See ADR-002.                                                           |
| Upstream health              | `core-routing`      | Instance-level: active health checks via SCG's reactive `WebClient`.   |
| Plugin SPI                   | `core-filters`      | `GatewayFilterFactory` remains the extension point; custom filters are regular beans loaded via `META-INF/spring.factories`. No Lua, no WASM. |
| Request validation           | `core-filters`      | Size + content-type + optional JSON schema validation filter.          |
| Circuit breaker              | `core-filters`      | Resilience4j reactive circuit breaker, configured per `GatewayRoute`.  |
| Migration tool               | `core-migration-tool` | See ADR-004.                                                         |

### 4. Everything is configured via CRDs

No `application.yml` knobs for end-user concerns. The data-plane bootstrap YAML carries
only the controller's address, the pod's zone name, and the Redis connection. All
user-facing configuration lives in CRDs: `GatewayRoute`, `GatewayConsumer`,
`GatewayCredential`, `GatewayZone`, `GatewayMeshPeer`, `GatewayPolicy`. See ADR-002.

## Consequences

### Positive

- **One JVM per pod.** Simpler ops, simpler observability, fewer CVEs to track, lower
  per-pod memory footprint.
- **Single filter chain.** The awkward Kong-to-jumper header contract disappears; the
  environment reconstruction workaround in `SpectreRoutingFilter.java:37` goes away.
- **Kubernetes-native config.** Operators use familiar `kubectl apply` workflows. Config
  drift between "Kong declarative YAML" and "jumper `application.yml`" stops existing.
- **Mesh semantics preserved.** The on-the-wire JWT contract between zones does not
  change, so zones can be migrated one at a time (see ADR-004).
- **Better hot-reload.** Routes change via gRPC push from the controller, not Kong admin
  API or pod restart.
- **Fewer moving parts in the hot path.** One TCP accept, one filter chain, one
  observability pipeline. Benchmarks project a 2--4 ms p99 improvement in-zone.

### Negative

- **We own route matching now.** Kong's router is a mature piece of software; SCG's
  predicate-based router is less feature-rich for edge cases (host regex, weighted routes).
  We mitigate by adding a handful of custom predicates up-front and keeping a compatibility
  test suite seeded from real Kong traffic.
- **We own rate limiting now.** Kong's rate-limiting plugin has years of production
  hardening. The `core-rate-limit` Redis token bucket is simpler but untested at our
  scale. We mitigate with a shadow-traffic rollout (ADR-004) and keep Kong as a fallback
  during phase 1.
- **We own a plugin SPI now.** This is mostly a documentation burden; the runtime is just
  Spring beans.
- **Migration effort is non-trivial.** 100+ `kong.yaml` files across clusters, dozens of
  custom consumers, some Spectre-specific routes. ADR-004 covers this.
- **Risk of re-implementing Kong's bugs.** We mitigate by having a dedicated
  compatibility test harness that replays captured Kong traffic through `gateway-core`
  and diffs behaviour.

### Neutral / requires follow-up

- **Plugin ecosystem.** We lose access to Kong community plugins. In practice, we only
  use a small set, and each is either re-implemented in Java or dropped.
- **Admin API.** Kong's admin API is replaced by `kubectl` + the admission webhook. Some
  runbooks will need updating.

## Alternatives considered

### Alt 1: Keep Kong, extend jumper

Minimal change but leaves every pain point above unaddressed. The Lua plugin fleet still
has to be maintained, the two-runtime-per-pod model stays, and the awkward header contract
remains.

### Alt 2: Migrate to Envoy + a new control plane

Envoy's xDS model is the gold standard, but we lose the ability to lift jumper's filters
directly; they would need to be rewritten as Envoy filters in C++ or Rust, or run as an
external-auth sidecar -- re-introducing the two-runtime problem. The team's Java
expertise is strong; an Envoy bet would be a 12-month rewrite, not a 3-month uplift.

### Alt 3: Replace Kong with Spring Cloud Gateway, keep jumper as a separate pod

Half-measure. Keeps the two-runtime latency tax and does not close the operational gap.
Provides no benefits a single consolidated JVM doesn't already provide.

## References

- Jumper README: `/Users/A85894249/claude-code/gateway-jumper/README.md`
- Kong header strip list: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/RoutingConfiguration.java:52-57`
- Environment-from-token workaround: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/filter/SpectreRoutingFilter.java:37`
- Netty metrics: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/NettyMetricsConfig.java` (PR #102, commit b3b176f)
- Zone health: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/ZoneHealthCheckService.java`
