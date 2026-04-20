<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# ADR-003: Zone model, mesh-JWT exchange, zone-health pub/sub, failover

- **Status**: Accepted
- **Date**: 2026-04-20
- **Deciders**: Stargate platform engineering, security architecture
- **Related**: ADR-001 (Kong-free), ADR-002 (CRD control plane), ADR-004 (migration path)

## Context

Stargate is a federated gateway: each **zone** (e.g. `aws`, `prod-de`, `openshift-1`)
runs its own gateway stack with its own identity provider realm. When a request arrives
in zone A for a service homed in zone B, the gateway must:

1. Authenticate the caller in zone A against zone A's realm.
2. Mint or fetch a **mesh token** that zone B's gateway trusts.
3. Forward the request to zone B carrying that mesh token, with the original caller token
   preserved for audit.
4. Continue to function when zone B is partially unhealthy, by falling over to a
   pre-declared alternate target.

This is the core business of jumper today. The mechanics are documented in jumper's
README at
`/Users/A85894249/claude-code/gateway-jumper/README.md:194-213` (mesh token scenario)
and `:309-316` (zone failover), and implemented across:

- `service/TokenGeneratorService.java` -- RS256 signing with injected claims
  (`kid`, `originZone`, `originStargate`, `env`, `requestPath`, `operation`, `typ=Bearer`).
- `service/TokenFetchService.java` + `service/TokenCacheService.java` -- OAuth client
  credentials against the peer zone's IdP, with Caffeine caching.
- `service/ZoneHealthCheckService.java` -- in-memory zone-health cache plus Micrometer
  gauges keyed by zone.
- `service/RedisZoneHealthStatusService.java` -- Redis pub/sub for zone-health gossip.
- `service/JumperConfigService.java` -- parses `jumper_config` for failover targets.
- `filter/UpstreamOAuthFilter.java` -- orchestrates all five token scenarios including
  mesh.
- `filter/RequestFilter.java` -- the main request-side state machine.

These pieces work, they are well-tested, and the on-the-wire contract (what a mesh JWT
looks like, what channel zone-health is published on) is stable across dozens of
deployments. **The central question for `gateway-core` is: do we keep this contract?**

## Decision

**Yes. We preserve jumper's zone/JWT/mesh/failover semantics one-for-one in `gateway-core`.**
The code moves into new modules (`core-token`, `core-mesh`, `core-zone-health`) but the
behavioural contract with other zones is unchanged.

This ADR documents the preserved model, the CRD mapping, and the narrow set of changes
we do make.

### 1. Zone identity

Each data-plane pod knows its own zone via bootstrap config: `gateway-core.zone=aws`.
The full definition of what a zone _is_ lives in a `GatewayZone` CRD (ADR-002):

- `name` -- the zone's short name, used as the `originZone` claim value and as the key in
  Redis zone-health messages.
- `realm` -- the IdP realm, used in constructed issuer URLs.
- `issuer` -- the issuer URL for tokens minted by this zone's gateway.
- `jwksUri` -- the JWKS endpoint for validation.
- `failover` -- an optional ref to another `GatewayZone` used as the failover target.

This replaces the realm-to-issuer mapping that currently lives in jumper's
`application.yml`. The runtime semantics are identical.

### 2. Mesh JWT exchange

When a `GatewayRoute` is homed in a foreign zone, the data plane selects the **mesh token
scenario** (jumper scenario 3, README lines 194-213). The flow:

1. The incoming request carries a valid caller token in `Authorization: Bearer ...`.
2. `core-inbound-auth` validates the caller token against the local zone's JWKS.
3. `UpstreamOAuthFilter` (lifted from
   `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/filter/UpstreamOAuthFilter.java`)
   sees the route is mesh-typed.
4. `TokenCacheService` looks up a cached mesh token keyed by `(peerZone, clientId)`.
5. On cache miss, `TokenFetchService` performs a `client_credentials` grant against the
   peer zone's `token_endpoint` using the `clientCredentialsRef` from `GatewayMeshPeer`.
6. The mesh token is placed in `Authorization`; the **original caller token is preserved
   in `Consumer-Token`** (unchanged from jumper).
7. The request is proxied to the peer zone's gateway.

The mesh JWT itself is still RS256, signed by the calling zone's signing key, with the
same claim set jumper produces today:

```
{
  "kid": "<matching certificate on peer Issuer service>",
  "typ": "JWT",
  "alg": "RS256"
}
{
  "sub": "<caller sub>",
  "clientId": "<caller clientId>",
  "azp": "stargate",
  "originZone": "<home zone of caller>",
  "originStargate": "<home gateway host of caller>",
  "env": "<env>",
  "requestPath": "<api_base_path>",
  "operation": "<HTTP method>",
  "iss": "<calling zone's issuer>",
  "typ": "Bearer",
  "exp": <propagated>,
  "iat": <propagated>
}
```

This is the same structure documented in
`/Users/A85894249/claude-code/gateway-jumper/README.md:139-153` (one-token) and adapted
in `:165-188` (LMS). By keeping the claim set identical, **peer zones running jumper
today interoperate with `gateway-core` with zero changes**, which is what makes the phased
migration in ADR-004 possible.

### 3. Token caching

We retain Caffeine as the cache backend (jumper's choice) with the same key scheme:
`(peerIssuer, clientId)` for mesh tokens, `(consumer, providerEndpoint)` for external
OAuth tokens. TTL is the min of the token's `exp` and a ceiling configured per
`GatewayMeshPeer` (default 5 min). Cache is local to each data-plane pod -- no shared
cache -- because the failure mode of a stale token is a single re-fetch, not a correctness
issue.

### 4. Zone-health pub/sub

We keep Redis pub/sub as the zone-health transport, with the channel name and message
shape inherited from jumper. Each gateway pod subscribes on startup; every zone (via its
leader, typically) publishes periodic `HEALTHY`/`UNHEALTHY` messages for the zones it
can reach.

Message shape (JSON, unchanged from `jumper/model/config/ZoneHealthMessage.java`):

```
{
  "zone": "aws",
  "status": "HEALTHY" | "UNHEALTHY",
  "timestamp": <epoch millis>
}
```

On receipt, `core-zone-health` updates the in-memory map and the Micrometer gauges
published per zone (see
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/ZoneHealthCheckService.java:43-56`
for the existing gauge-registration pattern; we lift it verbatim).

**What we change:** we now emit zone-health messages from `gateway-core` itself (not
from an external job), because the data-plane pod is the best-positioned observer of its
own zone's upstream health. A `GatewayZone` CRD opts into this publisher role; by default
only a quorum-elected leader publishes, to avoid message storms.

**What we don't change:** the channel, the message schema, the consumer side. A zone
running jumper continues to publish to and read from the same Redis channel, which is
what makes ADR-004's "zones migrate one at a time" feasible.

### 5. Failover

Failover today is encoded in the `jumper_config` header (base64 JSON) passed per request;
`JumperConfigService` parses it and picks an alternate upstream. In `gateway-core` we
retain the runtime behaviour but lift the configuration into the `GatewayZone.failover`
field and (for per-route overrides) into a `GatewayRoute.rules[].failover` field.

The selection logic is unchanged:

1. Before forwarding, consult `ZoneHealthCheckService.getZoneHealth(targetZone)`.
2. If `UNHEALTHY` and a `failover` target exists, rewrite the target URL to the failover
   upstream.
3. Continue with the normal mesh flow against the failover.

The diagram jumper publishes at `pictures/jumper_request_processing_with_failover.png`
remains an accurate description of the flow; the only change is that the failover
target comes from a CRD field, not a per-request header.

`jumper_config`-based overrides remain supported during migration -- if the header is
present, it wins over the CRD. This lets Kong + jumper zones continue to push config
through the header while `gateway-core` zones use CRDs. Once migration completes
(ADR-004), the header path is deprecated.

### 6. TLS and issuer pinning

`core-tls` lifts `TlsHardeningConfiguration` from
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/TlsHardeningConfiguration.java`
for both inbound and outbound connections. Mesh outbound calls additionally pin the peer
zone's expected JWKS / issuer host via `GatewayMeshPeer`, preventing a spoofed IdP from
issuing tokens our zone would then sign off on.

## Consequences

### Positive

- **Wire-compatible with existing jumper deployments.** Zones migrate independently.
- **Mature, tested logic is preserved.** We don't re-derive the claim set or the failover
  sequence.
- **CRD-ified configuration.** The operator no longer tunes zone behaviour via
  `application.yml` and base64-encoded headers; it's all declarative.
- **Health gauges as before.** Alerting, dashboards, and SLOs around
  `zone.health.status` continue to work unchanged.

### Negative

- **Carrying forward a legacy claim set.** Some of jumper's claims (`originStargate`,
  `env`) feel bespoke next to RFC-style claims, but they carry production meaning and
  downstream systems consume them. We keep them.
- **Redis remains a shared dependency.** It was a dependency before; we inherit it. A
  future ADR may evaluate replacing pub/sub with a gossip protocol.
- **`jumper_config` header remains parseable** during migration. This is a temporary
  compatibility surface we retire post-migration.

### Neutral

- Mesh token caching is still per-pod. We considered a shared Redis-backed cache but the
  TTL is short enough that the blast radius of duplicates is negligible.

## Alternatives considered

### Alt 1: Switch to SPIFFE/SPIRE workload identity

Conceptually cleaner. Deferred -- it's a multi-quarter project, requires SPIRE to be
deployed in every zone, and breaks wire compatibility. We keep SPIFFE on the roadmap as
a future ADR.

### Alt 2: Replace Redis pub/sub with a dedicated gossip protocol

Would remove a shared dependency. Costs us: a new network surface, a new set of failure
modes, a bespoke protocol to own. The cost/benefit didn't pay out given Redis is already
deployed.

### Alt 3: Push zone-health through the control plane CRDs

Appealing on paper; we'd have `GatewayZone.status.health`. Rejected because: health is
inherently a per-pod observation, the control plane isn't in the hot path, and Kubernetes
status subresources aren't sized for high-frequency health updates (and would flood the
watch channel).

## References

- Mesh scenario (jumper README): `/Users/A85894249/claude-code/gateway-jumper/README.md:194-213`
- Zone failover (jumper README): `/Users/A85894249/claude-code/gateway-jumper/README.md:309-316`
- Token generator: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/TokenGeneratorService.java`
- Zone health cache + gauges: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/ZoneHealthCheckService.java`
- Redis zone-health: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/RedisZoneHealthStatusService.java`
- Failover selection: `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/service/JumperConfigService.java`
- Mesh token flow diagram: `/Users/A85894249/claude-code/gateway-jumper/pictures/jumper2_mesh.png`
- Failover flow diagram: `/Users/A85894249/claude-code/gateway-jumper/pictures/jumper_request_processing_with_failover.png`
