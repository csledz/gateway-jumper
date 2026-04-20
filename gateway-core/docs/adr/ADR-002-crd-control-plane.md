<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# ADR-002: CRD-driven control plane

- **Status**: Accepted
- **Date**: 2026-04-20
- **Deciders**: Stargate platform engineering, control-plane tech lead
- **Related**: ADR-001 (Kong-free), ADR-003 (zone/JWT mesh), ADR-004 (migration path)

## Context

`gateway-core` (see ADR-001) removes Kong and its admin API, so we need a new source of
truth for all user-facing configuration. Today, that configuration is split across three
places:

1. **Kong declarative YAML** -- routes, services, consumers, credentials, plugins.
2. **Jumper `application.yml`** -- Horizon URL, Redis connection, zone name, realm-to-issuer
   mapping, tracing config.
3. **Operator tribal knowledge** -- Helm values that are copy-pasted from zone to zone.

Every item in this list is a configuration drift hazard. We want exactly one declarative
surface, version-controlled, validated before it reaches the data plane, reloadable
without a restart, and compatible with the Kubernetes Gateway API idiom our sibling
products already use.

## Decision

We will define a small set of **Gateway API-style CRDs** in a dedicated module
(`core-crd-api`), reconciled by a **single-leader controller** (`core-controller`) that
pushes compact snapshots to every data-plane pod over a long-lived gRPC stream.

### CRD catalogue

All CRDs live in group `gateway.telekom.de`, version `v1alpha1` (graduating to `v1` once
the contract is stable).

#### `GatewayRoute`

The primary resource. Models "send requests matching this predicate to this upstream
through this filter chain", analogous to Kubernetes Gateway API's `HTTPRoute`.

Key spec fields:

- `parentRefs` -- list of `GatewayZone` this route is homed in.
- `hostnames` -- list of host globs.
- `rules[]` -- match (path, method, headers, query), filters (auth, rate-limit, CORS,
  request-transform), backendRefs (upstream URL or service), timeouts.
- `policy` -- reference to a `GatewayPolicy` for cross-cutting settings.

Status is managed by the controller: `observedGeneration`, `acceptedReplicas` (how many
data-plane pods have loaded this version), and `conditions` (`Accepted`, `ResolvedRefs`,
`Healthy`).

#### `GatewayConsumer`

Identity and grouping metadata for a caller. Replaces Kong's `consumers` + `consumer_groups`
plus the `x-consumer-*` header set that jumper currently strips in
`/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/RoutingConfiguration.java:52-57`.

Key spec fields:

- `username`, `customId`
- `groups[]`
- `labels` / `annotations`
- `credentialRefs[]` -- pointers to `GatewayCredential` resources.

#### `GatewayCredential`

Authentication material for a consumer. Supports multiple credential types via a `type`
discriminator:

- `JwtIssuer` -- realm + JWKS URL + expected claims.
- `ApiKey` -- hashed secret + rotation policy.
- `ClientCredentials` -- OAuth client_id/secret pair for upstream mesh calls.
- `BasicAuth` -- for the legacy scenario jumper documents in README 238-254.

Secrets are stored as Kubernetes `Secret` refs; the controller resolves them and includes
only a Secret reference in the snapshot pushed to the data plane. The data plane reads
the Secret via a narrow RBAC.

#### `GatewayZone`

Identity of a Stargate zone. Replaces the hardcoded realm-to-issuer mapping in jumper's
`application.yml`.

Key spec fields:

- `name` (e.g. `aws`, `prod-de`)
- `realm`
- `issuer` URL
- `jwksUri`
- `failover` -- pointer to another `GatewayZone` used when this one is reported unhealthy
  (see ADR-003).

#### `GatewayMeshPeer`

Declares that this zone may call another zone over the mesh. Provides the materials
needed for a mesh-token exchange (jumper scenario 3).

Key spec fields:

- `peerZoneRef`
- `tokenEndpoint`
- `clientCredentialsRef` -- pointer to a `GatewayCredential` of type `ClientCredentials`.
- `redisChannel` -- zone-health pub/sub channel name (defaults to a convention).

#### `GatewayPolicy`

Cross-cutting, reusable config: rate-limit rules, CORS policy, circuit-breaker thresholds,
request-size limits, response-header enrichment, audit-log toggles. Attachable to a
`GatewayRoute`, a `GatewayZone`, or globally.

Key spec fields:

- `rateLimits[]`
- `cors`
- `circuitBreaker`
- `validation` (size, content-type, optional JSON schema)
- `audit`

### Controller pattern

The controller is a Spring Boot application using the **fabric8 kubernetes client**
informer framework. It follows a classic level-triggered reconciler loop:

1. **Watch** all six CRDs plus referenced `Secret` objects.
2. On any event, enqueue a reconcile for the owning `GatewayRoute` (the others are
   indirectly referenced).
3. **Reconcile**: resolve all references, validate invariants, build an immutable
   `Snapshot` object (routes + consumers + credentials + zones + mesh peers + policies).
4. **Diff** against the last snapshot. If identical, skip.
5. **Push** the new snapshot (or a delta) to every data-plane pod over gRPC.
6. **Wait** for acks; update each affected CRD's `status.conditions` with
   `Accepted=True` once a quorum of pods has acked.

The controller is **leader-elected** via a `Lease` object so we can run multiple replicas
for availability without split-brain reconciles. Only the leader pushes snapshots; the
followers run as warm standbys.

### Admission webhook

A **validating admission webhook** ships in the controller image. It enforces:

- Schema correctness (redundant with CRD OpenAPI, but catches cross-field invariants).
- Reference integrity (`backendRefs`, `credentialRefs`, `parentRefs` point to existing
  resources).
- Policy safety (e.g. rate-limit windows > 0, mesh peer client secret is a `Secret` ref,
  not inline).
- Security invariants (no `GatewayCredential` of type `JwtIssuer` may be attached to a
  `GatewayRoute` unless that route's parent `GatewayZone` allows the issuer).

The webhook rejects bad applies at the Kubernetes API boundary, so the controller never
sees invalid state. It's optional -- the controller is still defensive -- but strongly
recommended for operator ergonomics.

### Data-plane push protocol

The data plane connects to the controller over mTLS gRPC on pod startup. The protocol is
intentionally xDS-shaped but much smaller:

- `SubscribeSnapshots(zone, podId) -> stream Snapshot`
- `AckSnapshot(podId, snapshotVersion)`

Snapshots are **content-addressed** by a SHA-256 of their serialized form. The controller
sends deltas keyed on version; pods request full resyncs on reconnect or on a hash
mismatch.

### Why CRDs, not a custom REST API

- Kubernetes-native idiom: operators already know `kubectl apply`, GitOps tooling, RBAC,
  namespaces.
- Admission, validation, and audit are free via the API server.
- Finalizers give us safe deletion (e.g. don't delete a `GatewayCredential` that's still
  referenced).
- `status` subresources give us a clean place to report reconciliation state.
- CRDs are accessible from Go, Python, shell, Helm, Kustomize, ArgoCD, Flux -- anything
  that speaks Kubernetes.

### Why a push protocol, not a ConfigMap mount

We considered rendering the snapshot to a `ConfigMap` and letting the kubelet project it
into the data-plane pod. Rejected because:

- ConfigMap projection has a multi-second latency; we want sub-second route changes.
- ConfigMaps have a 1 MiB size limit. A production snapshot (thousands of routes) can
  exceed this easily.
- The data plane would need an inotify loop plus a parser for the full object on every
  change; deltas are not naturally expressible.
- Ack-based flow control (the controller knows which pods loaded which version) is
  trivial with gRPC and awkward with ConfigMaps.

## Consequences

### Positive

- **Single source of truth.** Every user-facing knob is a CRD spec field.
- **GitOps-ready.** Operators manage gateway config the same way they manage Deployments.
- **Fast hot-reload.** Route changes apply in well under a second end-to-end.
- **Typed references.** The admission webhook rejects dangling refs before they can
  break a live gateway.
- **Status visibility.** `kubectl get gatewayroute -o wide` shows which routes are
  accepted by how many pods.
- **Zero data-plane K8s coupling.** The data plane has no Kubernetes client; it only
  speaks gRPC to the controller. This makes it trivially portable (local dev, non-K8s
  environments) and keeps the hot path decoupled from control-plane bugs.

### Negative

- **Six CRDs is a learning curve.** We provide a Helm chart with realistic examples and
  the migration tool (ADR-004) generates correct CRDs from existing Kong + jumper config.
- **Controller is now a critical piece.** If the controller is down, routes cannot change
  -- though existing routes keep serving. We run the controller HA (leader-elected) and
  alert on snapshot-push latency.
- **gRPC push protocol is another piece to own.** Smaller than full xDS; we use the
  protobuf schema in `core-crd-api`.
- **Version skew between controller and data plane** has to be managed. We version the
  snapshot protobuf with explicit `oneof` fields and backward-compatible field numbers.

## Alternatives considered

### Alt 1: ConfigMap-projected config

Rejected (see above): too slow, too size-limited, no ack flow control.

### Alt 2: Direct Kubernetes watches from the data plane

Rejected: couples hot path to Kubernetes API availability, requires RBAC on every
data-plane pod, multiplies watch load by pod count.

### Alt 3: Reuse upstream Kubernetes Gateway API (`HTTPRoute` + `Gateway` + `GRPCRoute`)

Tempting. We **align with** the Gateway API idiom (spec shape, status conditions, parent
refs) but cannot fully adopt it because:

- Gateway API has no concept of consumer identity or mesh peering.
- Gateway API's filter vocabulary doesn't cover our OAuth token-exchange flows.
- `BackendTLSPolicy` et al. are still graduating and don't yet cover our needs.

We keep our CRDs semantically compatible where possible, so a future switch becomes a
rename rather than a rewrite.

### Alt 4: Envoy xDS

Overkill for our scale and introduces a second configuration vocabulary. Our CRDs are
domain-specific (consumer, mesh peer, zone) in ways xDS isn't.

## References

- Kubernetes Gateway API: https://gateway-api.sigs.k8s.io/
- fabric8 kubernetes client: https://github.com/fabric8io/kubernetes-client
- Existing jumper config surface (being replaced): `/Users/A85894249/claude-code/gateway-jumper/src/main/resources/application.yml`
- Kong headers stripped by jumper (replaced by `GatewayConsumer` resolution): `/Users/A85894249/claude-code/gateway-jumper/src/main/java/jumper/config/RoutingConfiguration.java:52-57`
