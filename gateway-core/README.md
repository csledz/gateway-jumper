<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# gateway-core

`gateway-core` is a greenfield, Kong-free API gateway landscape for Deutsche Telekom's
Stargate platform. It replaces the two-tier `Kong + jumper` sidecar topology with a single,
Spring-native data plane driven by a Kubernetes control plane. The project lifts the mature
parts of [gateway-jumper](https://github.com/telekom/gateway-jumper) (mesh JWT exchange,
zone-health pub/sub, failover, TLS hardening, Netty metrics) and absorbs everything Kong
was still doing in the old stack (inbound auth, rate-limiting, CORS, route matching,
consumer identity, dynamic routes).

## Why a new landscape

The current Stargate zone runs Kong in front of jumper. Kong adds a Lua runtime, a
separate admin/data split, and a plugin SPI that cannot be reconciled with the
Gateway API style of declarative routing we want organization-wide. Jumper already owns
the hard parts (RS256 mesh tokens, zone failover, upstream OAuth, Redis zone-health
pub/sub); Kong's remaining contributions are route matching, inbound auth, rate-limiting,
CORS, and consumer identity -- all of which are well-supported primitives in
Spring Cloud Gateway or easily added as filters. Running a single JVM per pod cuts latency,
removes a plugin surface, and unifies observability. See
[ADR-001](docs/adr/ADR-001-kong-free-architecture.md) for the full rationale.

## Landscape overview

`gateway-core` is a multi-module Maven project split across a **data plane** (reactive
proxy, filters, token services), a **control plane** (Kubernetes controller, CRD watchers,
admission webhook), and **shared libraries** (CRD models, token utilities, metrics).

```
                 +-----------------------+          +-----------------------+
   client ----> |  gateway-core (edge)  | <------> |  gateway-core (mesh)  | ----> upstream
                |   data plane pod      |          |   data plane pod      |
                +-----------+-----------+          +-----------+-----------+
                            ^                                  ^
                            |        CRD-driven config         |
                            +---------------+------------------+
                                            |
                                  +---------v---------+
                                  |  gateway-core     |
                                  |  controller pod   |
                                  +-------------------+
                                            |
                                  Kubernetes API (CRDs)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for runtime topology, data-plane vs. control-plane
split, and sequence diagrams for inbound requests, mesh zone-to-zone hops, and CRD
reconciliation.

## Module map

`gateway-core` is organized as 20 sibling Maven modules. A one-paragraph summary of each
module lives in [docs/MODULE_MAP.md](docs/MODULE_MAP.md). The top-level grouping is:

| Group                 | Modules                                                                      |
|-----------------------|------------------------------------------------------------------------------|
| Data plane            | `core-gateway`, `core-filters`, `core-routing`, `core-inbound-auth`, `core-rate-limit`, `core-cors` |
| Token & mesh          | `core-token`, `core-mesh`, `core-zone-health`                                |
| Control plane         | `core-crd-api`, `core-controller`, `core-admission`, `core-config-store`     |
| Cross-cutting         | `core-observability`, `core-tls`, `core-tracing`, `core-audit`               |
| Tooling & integration | `core-migration-tool`, `core-testkit`, `core-bom`                            |

## Documents in this repository

- [ARCHITECTURE.md](ARCHITECTURE.md) -- runtime topology and sequence diagrams.
- [docs/MODULE_MAP.md](docs/MODULE_MAP.md) -- one-paragraph summary per module.
- [docs/adr/ADR-001-kong-free-architecture.md](docs/adr/ADR-001-kong-free-architecture.md)
  -- why we drop Kong, what we lift from jumper, what's new.
- [docs/adr/ADR-002-crd-control-plane.md](docs/adr/ADR-002-crd-control-plane.md)
  -- Gateway API-style CRDs and controller pattern.
- [docs/adr/ADR-003-zone-jwt-mesh.md](docs/adr/ADR-003-zone-jwt-mesh.md)
  -- preserving zone/realm, mesh-JWT exchange, zone-health pub/sub, failover.
- [docs/adr/ADR-004-migration-path.md](docs/adr/ADR-004-migration-path.md)
  -- migrating from Kong + jumper sidecars, timeline, rollback.

## Quick start

These commands assume you have Java 21, Maven 3.9+, Docker, `kubectl`, and `kind` installed.
The project is still in the bootstrap phase; not every module is fleshed out, but the
quick-start below is the target workflow.

### 1. Build everything

```
./mvnw -T1C clean install -DskipTests
```

The reactor builds the CRD API first, then the control plane, then the data plane.
The `core-bom` module pins versions for downstream consumers.

### 2. Spin up a local cluster and install the CRDs

```
kind create cluster --name gateway-core
./mvnw -pl core-crd-api install && kubectl apply -f core-crd-api/target/crds/
```

This registers `GatewayRoute`, `GatewayConsumer`, `GatewayCredential`, `GatewayZone`,
`GatewayMeshPeer`, and `GatewayPolicy`. See
[ADR-002](docs/adr/ADR-002-crd-control-plane.md) for the CRD design.

### 3. Run the control plane and a data-plane pod

```
kubectl apply -f deploy/controller.yaml
kubectl apply -f deploy/gateway-core-edge.yaml
kubectl apply -f examples/hello-route.yaml
```

The controller reconciles `GatewayRoute` objects into the data-plane's internal route
table via a gRPC push channel.

### 4. Send a test request

```
curl -H "Authorization: Bearer <token>" http://localhost:8080/hello/ping
```

The request passes through inbound auth, route matching, rate limiting, upstream OAuth
(mesh token or one-token, depending on the route), and the response is returned to the
client. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full lifecycle diagram.

### 5. Migrate from Kong + jumper

A dedicated `core-migration-tool` reads a Kong declarative-config YAML (plus the
associated jumper `application.yml`) and emits the equivalent `GatewayRoute`,
`GatewayConsumer`, and `GatewayPolicy` manifests. See
[ADR-004](docs/adr/ADR-004-migration-path.md) for the staged rollout and rollback plan.

## Licensing

This project follows the [REUSE standard for software licensing](https://reuse.software/).
Each file carries an SPDX header; Apache-2.0 is the default. Third-party licenses live in
`LICENSES/`.

## Status

Pre-alpha. The documentation set (this README, `ARCHITECTURE.md`, and the four ADRs) is
the first durable artefact and defines the contract the implementation modules will fill
in.
