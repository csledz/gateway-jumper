<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: mesh-federation

## Status

SKELETON. Only data records defined. Registries, Redis pub/sub wiring, the
`FailoverSelector`, and the `GlobalFilter` are intentionally **not** present
here; they arrive in follow-up PRs. This PR only establishes the module
layout, dependency set, data records, bootstrap class, and this recipe.

## Purpose

The mesh-federation module turns a set of otherwise independent gateway
instances into a coordinated mesh. Each gateway runs inside exactly one
**zone** that belongs to a **realm** (a tenant-scoped administrative
boundary). Zones inside the same realm federate so that a caller hitting
zone A can be routed to a healthy peer zone B when the local upstream is
unavailable, without breaking identity. Identity propagation across zones
relies on the exchange of a bearer `<jwt>` minted by the issuer declared in
each zone's `issuerUrl`; the concrete exchange mechanism is the
responsibility of the auth-outbound module and is not implemented here.

## Zone lifecycle

Each gateway process knows its **local zone** from
`gateway.mesh.local-zone` (env `LOCAL_ZONE`). On startup the module:

1. **Registers** the local zone with the registry, marking it healthy.
2. **Publishes** a `ZoneHealth` heartbeat record every
   `gateway.mesh.health-interval-seconds` (default: 5s) on the shared Redis
   pub/sub channel `gateway-zone-status`. The record carries `zone`,
   `healthy`, `lastSeenEpochMs`, and the reporting zone (`reportedBy`).
3. **Subscribes** to the same channel to ingest heartbeats from peer
   zones and update its in-memory view.
4. **Reconciles** staleness: any zone whose last-seen timestamp is older
   than `3 * health-interval-seconds` is marked unhealthy in the local
   view. A subsequent fresh heartbeat promotes it back to healthy.

The lifecycle is deliberately eventually consistent; Redis pub/sub is the
transport, not the source of truth (the CRDs are). A pod losing its Redis
connection degrades to "local-only" mode: it keeps serving its own zone
but sees every other zone as stale after the timeout.

## Failover

Each inbound request resolves to a **candidate-zone list** derived from
the route configuration (local zone first, then peer zones in declared
preference order). The `MeshFederationFilter` iterates this list and
selects the first zone whose current view is healthy. Requests carry an
optional `X-Failover-Skip-Zone` header: every value of that header is
removed from the candidate list before selection, which lets the caller
pin a retry to a different zone than the one that just failed and lets a
peer gateway break forwarding loops by appending its own zone name.

Selection rules:

- If the local zone is healthy and not skipped, it wins.
- Otherwise the first healthy peer in the candidate order wins.
- If **no** candidate is healthy, the filter short-circuits the exchange
  with HTTP **503 Service Unavailable** and a JSON problem body naming
  the route and the empty candidate set.

The filter is a `GlobalFilter` with order **800** so that it runs after
authentication (which attaches the verified `<jwt>` to the exchange) and
before the proxy forwarder.

## CRD mapping

The module consumes two custom resources reconciled by the operator:

### `GatewayZone`

| Field                  | Type    | Notes                                         |
| ---------------------- | ------- | --------------------------------------------- |
| `spec.name`            | string  | Logical zone identifier.                      |
| `spec.realm`           | string  | Parent realm.                                 |
| `spec.stargateUrl`     | string  | Public ingress URL of this zone.              |
| `spec.issuerUrl`       | string  | OIDC issuer URL for discovery.                |
| `spec.internetFacing`  | boolean | Whether the zone is reachable from internet.  |

### `GatewayMeshPeer`

| Field                    | Type    | Notes                                       |
| ------------------------ | ------- | ------------------------------------------- |
| `spec.peerZone`          | string  | Name of the remote zone.                    |
| `spec.endpoint`          | string  | Data-plane base URL on the peer.            |
| `spec.tokenEndpoint`     | string  | Token endpoint used for `<jwt>` exchange.   |
| `spec.mtls`              | boolean | Require mTLS on the control channel.        |

CRD reconciliation itself is handled by a separate operator module; this
module only receives the resulting `Zone` and `MeshPeer` records.

## Implementation recipe

The follow-up PRs should add the classes below. Each is intentionally
small; every file must carry the SPDX header and stay under 40 lines
unless noted.

- `ZoneHealthRegistry.java` — thread-safe registry backed by a
  `ConcurrentHashMap<String, ZoneHealth>`. Exposes a Micrometer gauge per
  known zone (`gateway_mesh_zone_healthy{zone="..."}`) plus a gauge for
  the count of healthy zones. Pure data; no networking.
- `RedisZoneHealthPubSub.java` — owns the Redis reactive subscriber on
  `gateway-zone-status`, deserializes incoming `ZoneHealth` records into
  the registry, and runs the staleness sweep on a scheduled flux. Owns
  the periodic publisher that emits the local heartbeat on the same
  channel. Connection loss is logged and retried with exponential
  backoff; no records are persisted beyond the in-memory registry.
- `FailoverSelector.java` — pure function over
  `(candidateZones, skipZones, registry) -> Optional<Zone>`. No I/O, no
  Spring beans, fully unit-testable.
- `MeshPeerRegistry.java` — read-only view of the peers declared by the
  operator. Updated by a CRD watcher supplied from outside this module.
- `MeshFederationFilter.java` — `GlobalFilter` at order 800 that wires
  the registry, the selector, and the `X-Failover-Skip-Zone` header into
  the exchange. Returns 503 when the selector yields empty.
- `MeshFederationConfig.java` — `@Configuration` that binds
  `gateway.mesh.*` properties and exposes the beans above. No
  business logic.

## Verification

Standard module recipe:

```bash
./mvnw -pl gateway-core/mesh-federation clean compile
./mvnw -pl gateway-core/mesh-federation test
./mvnw -pl gateway-core/mesh-federation spotless:apply
```

The smoke test only asserts that the Spring context loads with Redis
auto-configuration excluded; it intentionally does not exercise the
registry or the filter (neither exists yet).
