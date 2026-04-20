<!-- SPDX-FileCopyrightText: 2026 Deutsche Telekom AG -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# gateway-core: Principal-Engineer Red-Team Critique

- **Status**: Review
- **Date**: 2026-04-20
- **Author**: Red-team reviewer (worktree `agent-a23e1ace`)
- **Scope**: Entire `gateway-core` landscape as it exists across all sibling
  `.claude/worktrees/agent-*/gateway-core/` trees at the time of review, the
  parent batch plan `~/.claude/plans/glowing-tickling-goose.md`, and the
  existing jumper code under `src/`.

> "Kong has shipped the same bug in the same plugin three times in five years
> because nobody ever deleted the test that let the bug through. Every rewrite
> is permission to delete those tests. You almost never should." — operating
> principle for this review.

This document is an adversarial read of the `gateway-core` design as landed so
far. It does three things:

1. Names the findings in priority order so a decision-maker can read five
   pages and know the top risks.
2. Catalogues 36 concrete findings with scenario-level failure modes and
   effort-sized recommendations.
3. Proposes five counter-ADRs that rise to architecture-level decisions.

A short "what the plan got right" section and a list of open questions for
human judgement closes the review. Citations are to file paths and line
numbers in the sibling worktrees or in jumper, so every claim can be
reproduced.

---

## 1. Executive summary

The top-five, in descending order of how much they will bite us in
production:

### 1. The data-plane control-plane channel is incoherent between ADR, controller, and helm chart

**What.** ADR-002 (`agent-a18b8a74/gateway-core/docs/adr/ADR-002-crd-control-plane.md:157-168`)
commits to an "xDS-shaped" mTLS gRPC stream with `SubscribeSnapshots` and
`AckSnapshot`. The controller worktree (`agent-aebc5bcd`) implements plaintext
HTTP `POST /config` from controller to data-plane URLs via
`spring-retry` and `WebClient`
(`agent-aebc5bcd/gateway-core/controller/src/main/java/io/telekom/gateway/controller/push/DataPlanePushService.java:90-141`).
The helm chart (`agent-af37afe4`) wires a `control-plane.admin-status-url`
into each data-plane pod so pods *pull* from the control plane
(`agent-af37afe4/gateway-core/helm-charts/data-plane/values.yaml:156-157`,
`control-plane/templates/NOTES.txt:20`), which is the opposite of the
controller's push model.

**Why it matters.** Three modules disagree on who connects to whom, which
protocol they speak, and how discovery works. When the controller starts up,
it has no way to learn which pods exist (the push list is statically
configured via `controller.dataplane.urls`); when a data-plane pod starts up,
it points at an `admin-status-url` that doesn't implement a
subscribe-snapshots endpoint. This design cannot ship as-is.

**Impact.** Rollout P0. Zero chance the current modules work end-to-end
until one canonical protocol is chosen and all three modules are aligned.

**Concrete fix.** Pick push-to-pod or pull-from-pod and commit to it in a new
ADR-005; remove the other path from all modules. I recommend *pull-from-pod*
because pod discovery is then the data-plane's problem (which the gRPC
bidirectional stream model handles natively) and horizontal scaling doesn't
require the controller to know pod IPs. See **F-001**, **F-002**, **F-007**,
and Proposed Counter-ADR §3.1.

### 2. `GatewayCredential` stores secret metadata in etcd and has a security-critical misuse pattern

**What.** The CRD (`agent-af88f0fb/gateway-core/api-crds/crds/gatewaycredential.gateway.telekom.de_v1alpha1.yaml`)
uses `spec.secretRef` to point at a Kubernetes Secret — good — but the
migration tool (`agent-aec8cfba/gateway-core/migration-tool/src/main/java/io/telekom/gateway/migration_tool/mapping/KongToCrdMapper.java:172-197`)
emits `GatewayCredential` objects with inline plaintext `spec.data.password`
and `spec.data.secret` fields. The CRD schema does not even declare a
`spec.data` field, so the output silently doesn't match the schema; at the
same time, the example at
`agent-af88f0fb/gateway-core/api-crds/examples/gatewaycredential-example.yaml`
sets no `secretRef` at all (valid because `type: jwt` doesn't require one,
but a reviewer skimming the examples will not realise a `type: basic`
credential must never have inline data).

Worse, **ADR-002** (§Admission webhook)
claims the webhook enforces "mesh peer client secret is a `Secret` ref, not
inline" — but there is no admission webhook implementation, and the CRD
schema has no anti-inline rule. There is nothing to reject a well-meaning
operator pasting a `client_secret: hunter2` into etcd.

**Why it matters.** etcd is not a secret store. Kubernetes Secret objects
are base64-encoded, not encrypted at rest by default; a `GatewayCredential`
with inline `spec.data.password` is strictly worse because it bypasses the
RBAC that applies to Secrets.

**Impact.** Security P0 for production. Inadvertent secret leakage into
GitOps repos and etcd backups.

**Concrete fix.** Make `secretRef` the *only* way to carry secret material in
`GatewayCredential`; add a CRD `x-kubernetes-validations` rule that forbids a
top-level `data`/`secret`/`password` field; fix the migration tool to emit
both a `Secret` manifest plus a `GatewayCredential.spec.secretRef`. See
**F-003**, **F-004**, **F-022** and Proposed Counter-ADR §3.2.

### 3. The fixed filter pipeline order (RATE_LIMITER=300 before POLICY=400) will spend Redis budget on rejected principals

**What.** The parent plan (`~/.claude/plans/glowing-tickling-goose.md`) and
the policy-engine `PolicyFilter` (`agent-a2eae0cf/gateway-core/policy-engine/src/main/java/io/telekom/gateway/policy_engine/filter/PolicyFilter.java:53`)
hard-code orders:
`REQUEST_VALIDATION=100, INBOUND=200, RATE_LIMITER=300, POLICY=400,
DISCOVERY=500, OUTBOUND=600, RESILIENCE=700, MESH=800, UPSTREAM=900`.

**Why it matters.** Running rate-limiter *before* policy means every request
that would have been denied by policy still costs us a Redis round-trip
(ZADD/ZREMRANGEBYSCORE/ZCARD, 4 round-trips in the worst case). Under
attack (credential-stuffing, abusive bot), that's N× amplification of Redis
load by the very principals we're about to deny. Conversely, running policy
first (which is local CPU) rejects early and only "real" traffic pays the
Redis cost.

Circuit-breaker after outbound-auth means a failing IdP can itself *drive*
the circuit-breaker because it's between CB and the real upstream. This
reverses who protects whom.

**Impact.** Scale P1. Under normal load it's a 2× Redis-call efficiency
loss. Under attack it's a Redis-outage enabler.

**Concrete fix.** Order should be:
`REQUEST_VALIDATION=100, INBOUND_AUTH=200, POLICY=300 (local), RATE_LIMIT=400 (Redis),
DISCOVERY=500, CIRCUIT_BREAKER=550 (protects outbound-auth), OUTBOUND_AUTH=600, MESH=700,
UPSTREAM=900`. See **F-009**, **F-010** and Proposed Counter-ADR §3.3.

### 4. SpEL as the default policy DSL is a sandbox escape waiting to happen

**What.** The policy-engine worktree ships two evaluators, SpEL and Rego
(`agent-a2eae0cf/gateway-core/policy-engine/src/main/java/io/telekom/gateway/policy_engine/spel/SpelPolicyEvaluator.java`,
 `rego/RegoPolicyEvaluator.java`). The SpEL evaluator uses
`SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods()` at
line 62-66. That *does* block top-level type references (so `T(java.lang.Runtime).getRuntime().exec(...)`
is out) but it allows any public instance method on any exposed object. The
context has `headers: Map<String, List<String>>`. `Map` is
`java.util.Map`; `List` is `java.util.List`; both expose
`getClass().getClassLoader()` — and while `#root.class.classLoader` is
typically off in `SimpleEvaluationContext`, a policy author can still invoke
methods that mutate state, throw exceptions, or run for unbounded time (a
malicious regex injected via `headerName` on the context). The CRD's
`spec.settings.policyEngine.engine` enum currently only offers `opa|cel`
(`gatewaypolicy.gateway.telekom.de_v1alpha1.yaml:159`), so SpEL is not
reachable from a CRD at all — which means the policy-engine worktree
implements a DSL that the CRD spec forbids.

**Why it matters.** Either SpEL is the default (per plan spec) and we're
exposed, or the CRD wins (per schema) and the whole SpEL implementation is
dead code. The team hasn't noticed the contradiction.

**Impact.** Security P1 if SpEL becomes reachable; otherwise wasted work.

**Concrete fix.** Drop the SpEL evaluator; make CEL the default
(Common Expression Language has a deterministic, sandboxable, bounded-time
evaluation model and is the Gateway API standard). Keep Rego as the
"powerful" evaluator behind a feature flag. Update the CRD enum to
`[cel, opa]` and delete `SpelPolicyEvaluator` entirely. See **F-015** and
Proposed Counter-ADR §3.4.

### 5. The migration tool produces output that does not apply to the declared CRDs

**What.** The migration tool emits `apiVersion: gateway.telekom.io/v1alpha1`
(`agent-aec8cfba/.../KongToCrdMapper.java:40`) but the CRDs are registered
under `gateway.telekom.de/v1alpha1`. It emits `spec.type: RateLimiting`,
`Cors`, `RequestValidator` (lines 223, 239, 254) but the CRD enum is
`ratelimit`, `cors`, `requestValidation` (`gatewaypolicy.*.yaml:52-57`). It
emits `GatewayCredential` with inline `spec.data` (lines 177-195) but the
CRD has no `data` field. It emits a `consumerRef` field on
`GatewayCredential` (line 174) but the CRD has no `consumerRef` — the
relationship is `GatewayConsumer.spec.credentialRefs[]`, i.e. the other
direction. It emits a `GatewayRoute.spec.upstream.host/port/protocol/path`
shape (lines 82-92) but the CRD requires
`spec.upstreamRef.{name, port, scheme}` and separately `spec.hosts`,
`spec.paths` at the top level. It emits `spec.stripPath: true` which has no
corresponding field in the CRD.

On top of that, only 3 of 20+ common Kong plugin types are mapped
(`rate-limiting`, `cors`, `request-validator`). Every other plugin —
critically `jwt` and `key-auth` which is how Kong currently authenticates
callers — is dropped to an "unmigrated" report. ADR-004 phase 1 relies on
`<0.1% divergence` on auth paths; with no `jwt` plugin migration, there is
no auth on the new gateway, so 100% of auth requests will diverge.

**Why it matters.** ADR-004 says migration is deterministic and idempotent.
The output is neither compatible with the CRDs nor functionally complete.
Phase 1 cannot reach its shadow-traffic success criterion.

**Impact.** Migration P0. Blocks ADR-004's 16-week rollout.

**Concrete fix.** Treat the migration tool as code whose output must pass
`kubectl apply --dry-run=server -f <output>` against the installed CRDs in
CI. Add `jwt` and `key-auth` plugin support (both produce `GatewayCredential
type=jwt` + `GatewayConsumer` links). Fix the shape mismatches. See
**F-026**–**F-029** and Proposed Counter-ADR §3.5.

---

## 2. Findings catalogue

Each finding follows the format:
- **Observation** — what the artifact actually does/says.
- **Risk** — the property it violates.
- **Scenario it breaks** — a concrete story that is testable.
- **Recommendation** — what to change, with patch shape.
- **Effort** — S (≤1 day), M (1–5 days), L (≥1 week).

### F-001 — Control-plane protocol disagreement between ADR and implementation

- **Observation.** `agent-a18b8a74/.../ADR-002-crd-control-plane.md:157-168`
  declares mTLS gRPC with `SubscribeSnapshots(zone, podId) -> stream Snapshot`
  and content-addressed delta pushes. The actual controller
  (`agent-aebc5bcd/.../DataPlanePushService.java:131-141`) uses `webClient.post().uri(url + "/config").contentType(MediaType.APPLICATION_JSON).bodyValue(snap).retrieve().toBodilessEntity().block()`
  with no TLS, no ack flow control, no delta, and `.block()` on what is
  documented as an async event handler.
- **Risk.** Foundational contract is false.
- **Scenario it breaks.** A reviewer reads the ADR, budgets for a gRPC
  server, estimates latency at 5ms, starts capacity-planning. The actual
  system sends full snapshots over HTTP/1.1 with `.block()` on a
  `@EventListener`, which means a slow data-plane stalls the controller's
  event loop. When 50 pods receive snapshots concurrently, push is `O(N)`
  serialized in a single thread pool (`@Async` default), not the "push
  deltas" the ADR claims.
- **Recommendation.** Either (a) rewrite `DataPlanePushService` as
  `grpc-spring-boot-starter` with proto defined in `core-crd-api`, or (b)
  rewrite ADR-002 to match what we actually have. The latter is cheaper but
  loses mTLS/ack/deltas. Recommend (a) as a new ADR-005; ship gRPC with
  unary-acked-stream semantics, content-addressing is 1 hour of SHA-256,
  delta compression is another 2 days. Remove `.block()` in favour of
  reactive chain.
- **Effort.** L.

### F-002 — Push direction is backwards for scale-out

- **Observation.** `DataPlanePushService.java:59, 74-77` maintains a
  `CopyOnWriteArrayList<String> dataPlaneUrls` that starts from a static
  `controller.dataplane.urls` config and can be added to via
  `registerDataPlane(String)`, but there is no auto-discovery of data-plane
  pods (no k8s endpoint-slice watch, no DNS resolution). The helm chart
  (`agent-af37afe4/.../data-plane/values.yaml:156-157`,
  `control-plane/templates/NOTES.txt:20`) instead gives each data-plane pod
  a `control-plane.admin-status-url` to pull from.
- **Risk.** The controller cannot deliver to pods it doesn't know about;
  new pods don't reach out in the current push model; pull from
  `admin-status-url` is unimplemented on the controller side.
- **Scenario it breaks.** HPA scales from 3 to 8 replicas during a
  traffic spike. The five new pods never receive routes. They start
  serving 404 on every request until the next manual
  `registerDataPlane(...)` call, which the chart does not perform.
- **Recommendation.** Flip to pull: data-plane pods open a long-lived
  gRPC stream to the controller service DNS name on startup; the
  controller publishes snapshot events into a `Sinks.Many<Snapshot>` and
  each stream is a separate subscriber. Registry becomes "who is
  currently subscribed" and is trivially correct across pod
  scale-in/scale-out. Alternatively, if sticking with push: have the
  controller watch `EndpointSlice` objects for the `gateway-core-data-plane`
  service and auto-register/deregister from that list.
- **Effort.** M (pull) or M-L (push with informer).

### F-003 — `GatewayCredential` lacks an anti-inline guard

- **Observation.** `gatewaycredential.gateway.telekom.de_v1alpha1.yaml:43-93`
  declares `secretRef` as the way to carry secret material, but places no
  constraint on what *else* may live in `spec`. The open-schema default of
  CRDs will happily accept any extra field.
- **Risk.** An operator copy-pasting an example from Kong's declarative
  YAML (which *does* inline secrets) will produce a CR with
  `spec.password: foo`; the CR is valid (because `type: basic` needs
  `secretRef`, not the absence of other fields), kubectl applies it
  without complaint, and the secret lives in etcd plaintext.
- **Scenario it breaks.** During migration, a zone-owner pastes
  `password: change-me` into a `GatewayCredential` "just to test". Nothing
  rejects it; the value is now in etcd backups, snapshot pushes, controller
  logs, and GitOps commit history.
- **Recommendation.** Add
  `x-kubernetes-validations: [{rule: "!has(self.data) && !has(self.password) && !has(self.secret) && !has(self.apiKey)", message: "inline secret material is forbidden; use secretRef"}]`
  to the `GatewayCredential` CRD. Pair with a pre-commit hook in the
  migration tool that regexes `spec.data|password|secret|apiKey` and
  refuses to commit.
- **Effort.** S.

### F-004 — `GatewayCredential` has no rotation primitive

- **Observation.** The CRD exposes `spec.secretRef.{name,namespace,key}` but
  no rotation or expiry metadata. Kong's `key-auth` had a trivial rotation
  flow: add a new key, delete the old one. With a singular `secretRef`, you
  can't overlap a rotation window.
- **Risk.** Rotating an API key becomes a big-bang operation; any in-flight
  request presenting the old key after the `kubectl apply` 401s.
- **Scenario it breaks.** SRE rotates an API key at 02:00. Some clients
  cache keys for 60s. The next minute returns 401 for ~200 req; alarms go
  off. SRE now has to coordinate the rotation window with consumer owners.
- **Recommendation.** Allow `spec.secretRefs[]` (plural). During rotation a
  credential may carry two refs; either matches until the old one is
  removed. Pair with a `spec.rotation.{startedAt,completedAt}` status
  block.
- **Effort.** S (CRD change) + M (auth-inbound logic).

### F-005 — No bootstrap secret story for the data-plane signing key

- **Observation.** `agent-af37afe4/.../helm-charts/data-plane/values.yaml:72-80`
  expects a user-supplied `keypair.existing-secret` with `signing.key` and
  `signing.crt`. The chart emits a placeholder template if absent. Jumper
  today loads its signing key from
  `src/main/java/jumper/config/KeyInfoService.java`, also from a Secret.
- **Risk.** Key rotation is a full pod restart; key compromise has no
  mitigation pathway short of rotating every pod simultaneously (which
  defeats HA).
- **Scenario it breaks.** A signing key is compromised. We need to revoke,
  rotate, and invalidate in-flight tokens. Current design requires a rolling
  restart of *every* zone's data-plane simultaneously, during which mesh
  traffic sees JWT validation mismatches.
- **Recommendation.** Support dual-key rollover: the data plane loads *all*
  signing keys in the keypair Secret (each JWK with a distinct `kid`) and
  signs with the first that is "active"; verification accepts any of the
  keys in JWKS. Operators do rotation as: add new key, wait JWKS-cache TTL,
  mark old inactive, remove after grace. This aligns with
  `TokenGeneratorService.java:88-143` which already uses `kid`.
- **Effort.** M.

### F-006 — `GatewayPolicy.type` oneOf switch is unmaintainable beyond 5 variants

- **Observation.** `gatewaypolicy.gateway.telekom.de_v1alpha1.yaml:50-179`
  uses a `spec.type` enum plus five sibling `spec.settings.*` sub-objects
  with five CEL validation rules
  (`self.type != 'ratelimit' || has(self.settings.ratelimit)` etc). Adding
  a sixth policy type (e.g. `waf`) means adding a new enum value, a new
  sub-object, and a new CEL rule. The schema has 150 lines dedicated to
  `spec` alone.
- **Risk.** Schema scales at O(N) in the number of policy types; CEL rule
  count is unbounded; a typo in any one rule is silent (kubectl accepts
  the CR and the controller later rejects it).
- **Scenario it breaks.** By 2027 we've added WAF, geo-IP, mTLS-pinning,
  body-redaction, and feature-flag policies. The single `GatewayPolicy`
  CRD is 500 lines; the fabric8 Java POJO is 9 mutually-exclusive nested
  builders; operators can't tell from `kubectl explain` which fields go
  with which type.
- **Recommendation.** Split by type:
  `RateLimitPolicy`, `CorsPolicy`, `RequestValidationPolicy`,
  `CircuitBreakerPolicy`, `AuthZPolicy`. Kubernetes Gateway API does this
  (`BackendTLSPolicy`, `RateLimitPolicy`, etc.); we claim in ADR-002 to
  "align with the Gateway API idiom" but then use a monolithic
  `GatewayPolicy`. Use a single shared interface in the Java layer
  (`Policy` → `RateLimitPolicy extends Policy`).
- **Effort.** M (and this is a breaking change we do *before* anyone
  installs the CRDs in prod).

### F-007 — `admin-status-url` is conflated with snapshot subscription

- **Observation.** The helm chart points data-plane pods at
  `control-plane.admin-status-url`
  (`agent-af37afe4/.../data-plane/values.yaml:157`). The plan (`Unit 15`)
  reserves `admin-status-api` for read-only status endpoints
  (`/admin/routes`, `/admin/zones`, etc.). Yet the chart's comment says
  "Where the proxy fetches route updates." — conflating status (read) with
  configuration (write-to-pod).
- **Risk.** Architectural split that should exist (control vs. query) is
  muddled at the wiring layer.
- **Scenario it breaks.** Someone adds a `POST /admin/routes` endpoint for
  "emergency route injection". It bypasses the CRD path, breaks the "CRD
  is source of truth" promise of ADR-002, and lives forever.
- **Recommendation.** Separate the two wires:
  `control-plane.snapshot-subscription.endpoint` (gRPC) and
  `control-plane.admin-status.url` (read-only HTTP). Remove the conflation
  from the chart README and data-plane `ConfigMap`.
- **Effort.** S.

### F-008 — Leader election claim is not evidenced in controller worktree

- **Observation.** ADR-002 (§Controller pattern) says the controller is
  "leader-elected via a `Lease` object". No `io.fabric8.kubernetes.client.extended.leaderelection`
  usage is visible in
  `agent-aebc5bcd/gateway-core/controller/src/main/java/`; informers are
  started unconditionally in `AbstractReconciler` and snapshots are pushed
  unconditionally from `DataPlanePushService`.
- **Risk.** Two replicas will both reconcile, both push, and both update
  status — textbook split-brain.
- **Scenario it breaks.** `replicaCount: 2` on the control plane helm chart
  (default HA). Both pods emit `ConfigSnapshotEvent`s. Every data-plane
  gets each snapshot twice. Status subresources thrash between
  observedGeneration values written by different leaders. CRD apply
  latency doubles.
- **Recommendation.** Add
  `LeaderElector.create(LeaderElectionConfigBuilder...)` in
  `ControllerConfiguration`; only the leader starts informers and the
  push service. Follower pods run with `ApplicationReadinessGate=false`
  until they win an election.
- **Effort.** M.

### F-009 — Rate limiter runs before policy (hot-path budget inversion)

- **Observation.** Plan specifies `RATE_LIMITER=300` before `POLICY=400`.
  Rate-limiter is Redis-backed (Lettuce, ADR-001 §"Rate limiting"). Policy
  is local CPU evaluation.
- **Risk.** Requests that policy would deny still consume Redis budget.
- **Scenario it breaks.** Credential-stuffing attack at 10k req/s, 99% of
  which have credentials the policy would deny. We spend 10k×4 = 40k Redis
  ops/s on requests we then deny anyway; Redis master hits CPU saturation;
  legitimate rate-limit checks slow to 50ms; production p99 collapses.
- **Recommendation.** Order: `REQUEST_VALIDATION=100, INBOUND_AUTH=200, POLICY=300 (cheap local), RATE_LIMIT=400 (expensive remote), DISCOVERY=500, ...`.
  Rationale: the cheap local checks (principal known, policy allow) gate
  the expensive Redis check. Document this as the default in
  ADR-005; allow override per-route for unusual cases (public health
  endpoints where you'd want rate-limit before auth).
- **Effort.** S.

### F-010 — Circuit breaker is placed where it cannot protect outbound-auth

- **Observation.** Plan specifies `OUTBOUND=600` then `RESILIENCE=700`.
  Resilience means circuit breaker. That means: when we call the IdP to
  mint a mesh token and the IdP times out, the circuit breaker sees the
  *effect* (timeout on the gateway-as-client → IdP) but the circuit it
  opens is about the upstream, not the IdP.
- **Risk.** A slow/failing IdP causes cascading failures across every
  route that shares the token-fetch, but the CB doesn't help.
- **Scenario it breaks.** Keycloak degradation at 10:00. Token fetches
  start timing out after 30s. Every mesh route waits 30s for a token
  before the CB maybe-fires on the upstream (which was never called).
  Connection pools saturate on token fetches; we hit pending-acquire
  timeouts; the proxy starts 503-ing routes whose upstream is perfectly
  healthy. This is exactly what the load-test-suite
  `SlowUpstreamConnectionGrowthScenario.java` measures for upstreams — but
  it measures the *upstream* side only.
- **Recommendation.** Two circuit breakers: one around outbound-auth
  (`CircuitBreaker.name = "idp-" + issuerUrl`, per-IdP) and one around
  upstream. Place the IdP CB *at* outbound-auth, before the fetch. Add a
  load-test scenario for IdP degradation.
- **Effort.** M.

### F-011 — Zone-health pub/sub has no message-drop reconciliation

- **Observation.** ADR-003 §4 keeps jumper's Redis pub/sub for
  zone-health. Redis pub/sub is best-effort: if a subscriber is slow or
  disconnected, messages are dropped.
  `jumper/service/RedisZoneHealthStatusService.java:60-68` ack-nothing;
  on a dropped message the subscriber's local state just stays stale.
- **Risk.** A `HEALTHY→UNHEALTHY` transition message is dropped; the
  subscribing pod keeps routing to a dead zone until the *next* message
  arrives (which may be several 5s periods later, or never if the
  publisher fails after the transition event).
- **Scenario it breaks.** Zone B's IdP dies at 10:00:00.
  Zone A publisher sends `UNHEALTHY` at 10:00:02. Zone A's one
  data-plane pod was briefly disconnected from Redis at 10:00:01-02 due
  to a TCP reset; it reconnects at 10:00:03 but has missed the message.
  Meanwhile the publisher in zone A flipped back to `HEALTHY` at
  10:00:07 after a brief self-heal. The next broadcast is at 10:00:07
  (`HEALTHY`); the missed `UNHEALTHY` never gets re-sent. The pod
  continues routing to zone B → 503s for 2 minutes.
- **Recommendation.** Replace pub/sub with Redis Streams (or keep pub/sub
  but add a periodic full-state snapshot in a `HASH` that subscribers
  re-read on every message and on reconnect). Alternatively, each
  subscriber keeps a per-zone `lastSeen` timestamp and treats zones whose
  `lastSeen` is older than 3× publish interval as `UNHEALTHY` (fail-safe
  default).
- **Effort.** M.

### F-012 — Token cache stampede: no single-flight primitive

- **Observation.** `jumper/service/TokenCacheService.java` + `TokenFetchService.java`
  use Caffeine with `.get(key, loader)`. Caffeine's `get(key, loader)`
  *does* provide single-flight *within* a JVM. But across 50 pods the
  first fetch after expiry fires 50 times concurrently.
- **Risk.** Thundering herd on the peer IdP. IdPs are often the weakest
  link and have modest RPS budgets.
- **Scenario it breaks.** Mesh token TTL of 300s. Across a 50-pod
  fleet, all pods loaded a token at minute 0. At minute 5 every pod
  fires a `client_credentials` request concurrently. Keycloak's token
  endpoint is sized for 20 concurrent connections; 30 requests time out;
  the `ClientErrorException` drives retries which amplify the storm.
- **Recommendation.** Two layers of single-flight:
  (1) within-pod via Caffeine; (2) cross-pod via a Redis
  `SET NX EX` "refresh-in-progress" lock keyed on
  `(peerIssuer, clientId)`. The lock-holder fetches, writes the token to
  a shared Redis cache, and releases. Losers read the cached token. This
  requires Redis anyway (already used by rate-limit + zone-health).
  Cost: one extra Redis round-trip on cache-miss; typical cost is paid
  once per TTL per cluster, not per pod.
- **Effort.** M.

### F-013 — Mesh JWT replay has no jti or nonce

- **Observation.** ADR-003 §2 declares the mesh JWT claim set. There is
  no `jti`, no nonce, no per-request binding. TTL is the only protection.
- **Risk.** A mesh JWT captured in transit can be replayed until its
  `exp`. If the TTL is 5 minutes (typical), that's a 5-minute replay
  window.
- **Scenario it breaks.** An attacker with TLS-terminating capability
  on a zone boundary (a compromised middlebox, misconfigured MITM
  inspection proxy, etc.) captures a mesh JWT. The JWT carries
  `sub=caller`, which the downstream gateway trusts. The attacker
  replays for 5 minutes, issuing arbitrary requests as the captured
  caller.
- **Recommendation.** Add a `jti` claim in `TokenGeneratorService`
  (UUID) and a Redis `SET NX EX` check on the receiver side keyed on
  `jti` with TTL = token `exp - iat`. `SET jti:<jti> 1 NX EX 300`
  returns 0 if already seen → 401. Cost: one Redis round-trip per mesh
  hop; amortizes into the existing mesh flow.
  Pair with `aud` = receiving zone's gateway URL to prevent
  cross-gateway replay.
- **Effort.** M.

### F-014 — No key-ID pinning on mesh peers

- **Observation.** ADR-003 §6 says mesh outbound "pins the peer zone's
  expected JWKS / issuer host via `GatewayMeshPeer`". But
  `gatewaymeshpeer.*.yaml` has no `expectedKid` or `pinnedJwksSha256`
  field. The data plane will trust *any* key on the peer's JWKS URL,
  whose content is controlled by the peer IdP operator.
- **Risk.** Insider compromise of the peer IdP (or MitM on the JWKS
  URL) injects a new key; our data plane accepts tokens signed by it.
- **Scenario it breaks.** Peer zone's Keycloak is misconfigured to
  publish a key we did not expect. Our zone accepts tokens signed with
  that key because it rotates in via the JWKS URL. No alert fires.
- **Recommendation.** Two-tier trust: `GatewayMeshPeer.spec.trustedKids[]`
  (allow-list) and/or `pinnedJwksSha256`. Hard fail closed if the peer
  publishes a key whose `kid` is not in the allow-list.
- **Effort.** S.

### F-015 — SpEL policy evaluator is both unsafe and unreachable

- **Observation.** `SpelPolicyEvaluator.java:62-66` uses
  `SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods()`.
  That is the safer-SpEL mode, but it still allows any method invocation
  on exposed objects. The CRD enum
  (`gatewaypolicy.*.yaml:159`) is `[opa, cel]` — SpEL is not listed.
- **Risk.** Dead code path today; foot-gun tomorrow.
- **Scenario it breaks.** Someone adds `spel` to the CRD enum "for dev
  convenience". A malicious or typo'd policy source is evaluated; at
  best it hangs a request thread, at worst it mutates shared state.
- **Recommendation.** Delete `SpelPolicyEvaluator.java` entirely. If
  a quick-and-easy DSL is needed, use CEL (via `cel-java`); it has
  bounded evaluation, no reflection, and is the Gateway-API standard.
  If Rego is retained for power-user policies, document it clearly and
  gate the URL-based OPA fetch behind signed manifests.
- **Effort.** S (delete) or M (replace with CEL).

### F-016 — Plugin SPI hot-reload has an unreferenced-classloader-reuse bug

- **Observation.** `PluginLoader.java:108-157` calls
  `closeOpenClassLoaders()` at line 115 at the top of
  `loadFromDirectory()`, then creates a new `URLClassLoader` at line 139
  and adds it to `openClassLoaders`. But the *old* plugin instances are
  still referenced by `registry.replaceAll(...)` at line 85. When the
  reload happens, the old registry is replaced, but if anyone still
  holds a reference to an old plugin instance (a filter that was
  constructed once and cached, a Spring singleton created from a plugin
  class) the old classloader is rooted and cannot be GC'd. `close()` on
  `URLClassLoader` only releases file handles, not the loader.
- **Risk.** Metaspace growth over the lifetime of the process. Known
  pattern; hits long-running gateway pods in production.
- **Scenario it breaks.** Operator rotates plugin JARs weekly. Each
  reload holds some references via gauges, listeners, or cached filter
  instances. After 50 reloads metaspace is full, GC does a stop-the-world
  metaspace compaction (if `-XX:+UseStringDeduplication`/G1 supports it
  — mostly it doesn't), and the pod is OOM-killed on
  `java.lang.OutOfMemoryError: Metaspace`.
- **Recommendation.** Do not hot-reload into the same JVM. Treat plugins
  as immutable per-pod: a plugin JAR change triggers a rolling restart
  of the deployment. Remove `startWatching()` / `watchLoop` from
  `PluginLoader.java`. If hot-reload is a hard requirement, adopt a
  well-understood plugin host (JPMS layers via `ModuleLayer.defineModules`
  with explicit `close` semantics, or a sandboxed child process).
- **Effort.** M-L (the simple fix is delete the watcher; the principled
  fix is a proper plugin host).

### F-017 — Plugin SPI has no signing, no allow-list, no capability model

- **Observation.** `agent-a87e2877/.../plugin_spi/api/GatewayPlugin.java` plus
  `ServiceLoader` discovery means any JAR dropped into the watched
  directory (`PluginLoader.java:118-142`) gets loaded into a child
  `URLClassLoader` that has the application classloader as parent —
  so plugins have full access to the gateway's classes, the Spring
  context, and the JVM. There is no signature check on the JAR, no
  allow-list of class names, no deny-list of packages, and no
  capability restriction (plugins can read Secrets from the filesystem,
  make outbound connections, call `System.exit`).
- **Risk.** Supply-chain and insider-threat. A plugin JAR with a
  typo-squatted artifact name dropped into the plugins directory runs
  inside the gateway process with full privileges.
- **Scenario it breaks.** A CVE / supply-chain breach in an upstream
  artifact we thought we were using. Our gateway loads the compromised
  JAR on the next filesystem change. It reads the keypair Secret from
  `/var/run/secrets/gateway-core` and exfils tokens. No signal fires.
- **Recommendation.** Three layers:
  1. Signed plugin JARs only: verify Sigstore / cosign signature at
     load time; refuse unsigned.
  2. Plugin allow-list in `PluginSpiProperties` — only pre-declared
     plugin names load.
  3. Running `core-gateway` as a separate OS user with a seccomp
     profile (`seccompProfile: RuntimeDefault` is already in the helm
     chart at `values.yaml:43-44` — good start) and a read-only
     filesystem except for `/tmp` and the plugin directory. This
     defense-in-depth is realistic; a full Java capability model (the
     old `SecurityManager`) is deprecated and not worth reviving.
  Alternative: run the plugin host in a child JVM with a narrow RPC
  surface. Higher engineering cost but the only principled fix if
  third-party plugins are in scope.
- **Effort.** L.

### F-018 — Tracing redaction list is inherited from jumper and is dangerously narrow

- **Observation.** `jumper/src/main/resources/application.yml:110` sets
  `filter-param-list: X-Amz-.*,sig`. This list is only applied to query
  parameters in the tracing span name
  (`TracingConfiguration.java:97-143`). It does not redact headers
  (`Authorization`, API keys in headers, basic-auth URL-embedded creds,
  `X-Api-Key`), and does not redact request bodies.
- **Risk.** Every Bearer token, API key, and basic-auth string the
  gateway sees lands in traces. Jaeger and OTLP backends are usually
  multi-tenant and long-retention.
- **Scenario it breaks.** `gateway-core` as the Kong replacement
  absorbs inbound auth. A client sends `Authorization: Bearer
  eyJhbG...`. Our span records the full URL and (in some SCG defaults)
  request-header attributes. Any ops engineer with Jaeger access reads
  tokens.
- **Recommendation.** Ship a comprehensive redaction set out of the box
  and *document* it:
  `filter-param-list: X-Amz-.*,sig,access_token,id_token,refresh_token,client_secret,code,state,apikey,api_key,token`;
  add a header redactor that strips `Authorization`, `Proxy-Authorization`,
  `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `X-Consumer-*`.
  Add a body-redactor for known content types (JSON keys matching
  `(?i)(password|secret|token|key|auth)`). Bake these into
  `core-tracing` as non-overridable unless explicitly opted-out with a
  warning log per request.
- **Effort.** S (patterns) + M (header/body redactor + tests).

### F-019 — `x-consumer-*` header emission is a Kong-bug transcription, not a design

- **Observation.** ADR-001 §"Consumer identity" promises "emits the
  `x-consumer-id`, `x-consumer-custom-id`, `x-consumer-groups`,
  `x-consumer-username` headers (the same set Kong used to emit and that
  jumper strips)". ADR-004 adds that we keep them to preserve
  compatibility.
- **Risk.** We're preserving Kong's namespace pollution forever. These
  headers have a history of getting forged (clients inject
  `x-consumer-id` directly, hoping the gateway forwards them as-is) and
  have no trust model beyond "Kong is there".
- **Scenario it breaks.** A client sends `X-Consumer-Id: admin` and the
  gateway's header list gets built in the wrong order — the client's
  header wins. Silent privilege escalation.
- **Recommendation.** Use a namespaced header set
  (`X-Gw-Consumer-Id`, `X-Gw-Consumer-Groups`) that the gateway *always
  overwrites and never trusts inbound*. Provide a temporary
  "compat-mode" flag on `GatewayRoute.spec` that also emits the old
  `x-consumer-*` set for downstreams that haven't migrated yet, with a
  sunset date. Always strip the legacy inbound variants from client
  requests regardless of mode.
- **Effort.** S (define) + M (ensure always-strip on inbound in
  `core-inbound-auth`).

### F-020 — Rate-limiter's sliding-window correctness under burst is not asserted

- **Observation.** Plan says "Redis-backed sliding-window rate-limiter"
  with ZADD/ZREMRANGEBYSCORE/ZCARD/PEXPIRE in one Lua script.
  No rate-limiter module has landed in any worktree I can see. But the
  design sketch is ambiguous: a true sliding window requires storing
  individual request timestamps; a fixed-window approximation slides at
  window boundaries and allows 2× burst at the edge.
- **Risk.** Under bursts around window boundaries the limiter allows 2×
  the declared rate.
- **Scenario it breaks.** A 100 req/s limit with a 1s window. Client
  sends 100 req at t=0.999s and 100 req at t=1.001s. True sliding
  window: 100 req in trailing 1s → limit enforced. Fixed window:
  window[0] had 100, window[1] has 100 → both allowed, effective 200
  req/s.
- **Recommendation.** Pick one semantics and document it. For production,
  recommend a token-bucket with leak rate = limit/window and
  burst = limit (cheaper: one KEY, two commands
  `GET + EXPIRE` per check). If sliding window is required, use
  ZADD+ZREMRANGEBYSCORE+ZCARD Lua script but test with the burst scenario
  above as a Cucumber step.
- **Effort.** M (implementation) + S (scenario test).

### F-021 — Rate-limiter's Redis failover behavior is unspecified

- **Observation.** The rate-limiter is Redis-backed. ADR-001 and the
  plan do not specify behavior when Redis is unavailable.
- **Risk.** Two defensible positions (fail-open, fail-closed) are both
  wrong most of the time; an unstated default will be whatever the
  implementation happens to do, which is usually fail-open by accident.
- **Scenario it breaks.** Redis outage at 10:00. If fail-open, every
  consumer has no limit; a misbehaving consumer at 10k req/s overwhelms
  the upstream. If fail-closed, legitimate traffic 429s for the duration
  of the outage.
- **Recommendation.** Default fail-open with an emergency global cap
  enforced in-memory per pod: a bloom-filter-counted approximation of
  per-consumer rate. That way Redis outage ≠ unlimited. Configurable
  per-policy: `onRedisFailure: {fail-open | fail-closed | soft-limit}`.
- **Effort.** M.

### F-022 — Admission webhook exists only in documentation

- **Observation.** ADR-002 §"Admission webhook" describes schema
  cross-field invariants, reference integrity, policy safety, security
  invariants. No admission-webhook module has landed; the plan does not
  list `core-admission` as a separately-scoped unit, and the
  `MODULE_MAP.md` mentions `core-admission` as a separate module at
  line 124-129 but it isn't in the 20-unit work breakdown.
- **Risk.** The webhook is the only place the "inline secrets are
  forbidden" invariant is claimed to live (F-003). Without it, invalid
  CRs reach the controller, which is defensive in spec only.
- **Scenario it breaks.** A bad CR breaks reconciliation;
  `observedGeneration` gets stuck; the admin-status-api reports
  nothing special because the controller didn't publish status
  conditions for a resource it couldn't parse.
- **Recommendation.** Either ship `core-admission` or move all
  invariants to OpenAPI `x-kubernetes-validations` rules embedded in
  the CRD (which works without a webhook). I'd prefer the latter —
  fewer moving parts.
- **Effort.** M.

### F-023 — No versioning discipline: `v1alpha1` today, no migration plan

- **Observation.** All six CRDs ship as `v1alpha1`. ADR-002 casually
  notes "graduating to `v1` once the contract is stable". No
  `conversion` strategy is declared. No indication what will trigger a
  `v1beta1` / `v1` bump.
- **Risk.** First schema change = silent data loss on `kubectl apply` if
  conversion isn't wired up, or breaking change for every operator.
- **Scenario it breaks.** Three months in, we split `GatewayPolicy` by
  type (F-006). Existing CRs fail to reapply; zones can't `helm upgrade`.
- **Recommendation.** (a) Declare a conversion webhook strategy up
  front, or (b) commit to additive-only changes until `v1beta1`. Land a
  `docs/adr/ADR-006-crd-versioning.md` before any CRD goes into a
  production namespace.
- **Effort.** S (doc) + M (conversion webhook).

### F-024 — Snapshot is unsigned

- **Observation.** `SnapshotBuilder.java` generates a `snapshotId =
  UUID.randomUUID()` and a `generatedAt`; `DataPlanePushService.push()`
  sends JSON over plain HTTP. No signature, no integrity check.
- **Risk.** An attacker on the control-plane → data-plane network can
  forge snapshots. Anyone with POST access to `{dataplane}:port/config`
  can inject routes.
- **Scenario it breaks.** An internal-network misconfiguration exposes
  the data-plane's config port. Attacker POSTs a malicious snapshot
  routing `/login` to an attacker-controlled upstream. This is now a
  credential-harvesting gateway.
- **Recommendation.** Sign snapshots with the controller's private key;
  verify on receipt using a pinned public key in the data-plane
  bootstrap secret. Add mTLS at the transport layer too (belt and
  braces). Deny any `/config` POST that doesn't carry a valid signature
  header.
- **Effort.** M.

### F-025 — Snapshot lacks a "last-known-good" recovery path

- **Observation.** ADR-002 §Consequences claims "existing routes keep
  serving" if the controller is down. But the controller currently pushes
  full snapshots over HTTP; there is no local persistence of the last
  snapshot on the data-plane side.
- **Risk.** A data-plane pod restart while the controller is down
  results in an empty route table → every request 404.
- **Scenario it breaks.** Kubernetes control-plane upgrade; our
  controller deployment is unavailable for 5 minutes. A data-plane pod
  crashes in that window and gets rescheduled. It starts up, tries to
  fetch/subscribe, fails, has no routes. Pod is alive but serving 404;
  readiness probes on the default probe-path may pass because
  `/actuator/health/readiness` doesn't check route-table completeness.
- **Recommendation.** Persist the last accepted snapshot to an emptyDir
  volume on receipt; on startup, load from disk before attempting
  control-plane subscription; treat startup-with-no-controller as
  "serve from last snapshot, mark readiness=true for in-zone traffic
  only, don't accept mesh peer federation until a fresh snapshot lands".
  Also: readiness probe should check `routeTable.isLoaded()`.
- **Effort.** M.

### F-026 — Migration tool emits CRDs that do not match the declared schema

- **Observation.** Detailed in Executive summary §5.
  `KongToCrdMapper.java:40` uses wrong API group (`telekom.io` vs.
  `telekom.de`); lines 223, 239, 254 use wrong `type` enums; lines
  172-197 emit inline `data.password`; line 174 uses non-existent
  `consumerRef`; lines 82-92 use wrong upstream/match shape.
- **Risk.** Every emitted CR fails `kubectl apply` against the real
  CRDs.
- **Scenario it breaks.** ADR-004 Phase 0 "review output with zone
  owners. Fix tool gaps until a happy path zone converts with zero TODO
  markers." cannot complete — the tool doesn't even produce applyable
  YAML, let alone zero-TODO output.
- **Recommendation.** Add a CI gate: the migration tool's fixtures are
  piped through `kubectl apply --dry-run=server -f - --validate=true`
  against a kind cluster with the real CRDs installed. This catches
  shape drift the instant it happens.
- **Effort.** S (gate) + M (fixes to the mapper).

### F-027 — Migration tool drops the Kong `jwt` plugin, which is the entire inbound-auth path

- **Observation.** `KongToCrdMapper.java:205-211` only maps
  `rate-limiting`, `cors`, `request-validator`; everything else is
  recorded in the unmigrated report. Kong's `jwt` and `key-auth` plugins
  are how consumers authenticate today — these are the most important
  mappings, not the optional extras.
- **Risk.** The migrated zone has no inbound auth at all.
- **Scenario it breaks.** ADR-004 Phase 1 shadow-traffic diff — every
  auth'd request on the new stack passes anonymously; the old stack
  rejects unauth'd requests. The diff rate is ~100% on any auth'd route.
- **Recommendation.** Add mappings for `jwt` → `GatewayCredential type=jwt`
  attached to consumers; `key-auth` → `GatewayCredential type=apikey`;
  `basic-auth` → `GatewayCredential type=basic`. Add mapping for the
  Kong `ip-restriction` plugin → `GatewayPolicy type=authz` (new type)
  or explicit `allowedCidrs[]` on the route. Document unmapped plugins
  as a *design choice* (we don't have WAF, so Kong-WAF entries go to
  unmigrated), not a gap.
- **Effort.** M.

### F-028 — Migration tool does not emit `GatewayZone` or `GatewayMeshPeer`

- **Observation.** `KongToCrdMapper.java` handles `KongService`,
  `KongConsumer`, `KongCredential`, `KongPlugin`. It does not read
  jumper's `application.yml` or the zone Helm values; it does not emit
  `GatewayZone` or `GatewayMeshPeer`. ADR-004 claims these will be
  emitted ("`GatewayZone.yaml` (one per zone...)", "`GatewayMeshPeer.yaml`
  (one per zone we mesh-to...)").
- **Risk.** ADR-004 promises an end-to-end conversion; the tool delivers
  only the Kong-side half.
- **Scenario it breaks.** A zone owner runs the migration tool, applies
  the output, and the gateway has no zone identity → no mesh-JWT
  signing → every outbound mesh call fails.
- **Recommendation.** Extend the CLI with `--jumper jumper-application.yml`
  and `--helm values-zone.yaml` inputs (already declared in ADR-004);
  parse them; emit `GatewayZone` + `GatewayMeshPeer` CRs.
- **Effort.** M.

### F-029 — Migration tool's idempotency claim is unverified

- **Observation.** ADR-004 says the tool is "deterministic and idempotent:
  running it twice on the same input produces identical output." No test
  asserts this; fixture files at
  `agent-aec8cfba/.../target/test-classes/deck-fixtures/*.yaml` are
  Cucumber features for single-run behaviour.
- **Risk.** A map iteration order or `UUID.randomUUID()` slip makes two
  runs diverge; downstream GitOps detects a no-op as a drift and
  generates endless PRs.
- **Scenario it breaks.** ArgoCD sync loop. The tool is rerun nightly;
  every morning there's a diff; operators can't tell real changes from
  noise.
- **Recommendation.** Add a Cucumber scenario: "Given fixture X, When I
  run the tool twice, Then the byte-for-byte output is identical." Use
  `LinkedHashMap` throughout for deterministic iteration; avoid
  `UUID.randomUUID()` for CR names (derive names deterministically from
  source).
- **Effort.** S.

### F-030 — Load-test "correlation" pass/fail is too lax

- **Observation.** `SlowUpstreamConnectionGrowthScenario.java:127-134`
  asserts on a Pearson correlation between elapsed time and
  open-connection count. The plan mentions "correlation between upstream
  latency and open connections" as the pass/fail signal.
- **Risk.** A correlation of +0.6 passes the spirit of the test but
  doesn't tell you whether the connection pool behaved correctly — it
  only tells you open-conns trended upward.
- **Scenario it breaks.** Regression lands that leaks 1 connection per
  request. Correlation is ~+1.0; test passes; leak is in prod. Same
  correlation as healthy Little's-Law scaling.
- **Recommendation.** Assert three things:
  (1) correlation > 0.8 during the ramp (Little's Law holds),
  (2) `max(openConns) < maxConnections` (no saturation in healthy case),
  (3) after the ramp ends and driver stops, `openConns` drops to
      steady-state within 30s (connection release works).
  Add a specific numeric threshold on `max(openConns)` given the known
  RPS × latency profile.
- **Effort.** S.

### F-031 — HdrHistogram correctness is fine in the driver, hazardous in the data plane

- **Observation.** `LoadDriver.java:56` uses `ConcurrentHistogram` which
  is lock-free and correct under multi-thread access — so the driver
  itself is fine. The hazard is different: `LoadDriver.java:167` calls
  `.subscribeOn(Schedulers.parallel())` on a Reactor chain that records
  latency via `recordLatency` (line 170). `ConcurrentHistogram` handles
  multi-thread writes, but the plan's phrased concern ("HdrHistogram's
  ThreadLocal model clash with Project Reactor") is real for the
  *data-plane's* future per-route latency histograms: a developer who
  reaches for `Histogram` (not `ConcurrentHistogram`) or for
  `SynchronizedHistogram` will silently lose accuracy under Reactor
  scheduler-hopping.
- **Risk.** Data-plane p99 reported in-process diverges from the p99
  measured externally; operators debug phantom latency regressions.
- **Scenario it breaks.** A dev adds a per-route `Histogram` (not
  `ConcurrentHistogram`) timer to `core-observability`. Reactor hops
  threads between `.doOnNext` and `.doOnSuccess`. Writes race;
  `getValueAtPercentile` returns junk. On-call thinks p99 doubled and
  rolls back a harmless change.
- **Recommendation.** In `core-observability`, do *not* expose raw
  HdrHistogram. Use Micrometer's `Timer.builder(...).publishPercentileHistogram()`
  which is reactor-safe and integrates with Prometheus. Keep
  `ConcurrentHistogram` in the load-driver only (that code is blocking
  by design). Add a spotbugs/arch-unit rule that bans
  `org.HdrHistogram.Histogram` imports outside `load-test-suite`.
- **Effort.** S.

### F-032 — Load-test does not exercise the mesh hop

- **Observation.** The scenarios
  (`SlowUpstreamConnectionGrowthScenario`, `SpikeScenario`,
  `SteadyStateScenario`, `BackpressureScenario`, `ResilienceUnderLoadScenario`)
  all talk directly from driver → gateway → upstream. No scenario
  involves zone A → zone B → upstream.
- **Risk.** The most complex hot path (mesh token fetch, cross-zone
  forwarding, failover on zone-health) is not load-tested.
- **Scenario it breaks.** Production rollout uncovers a lock contention
  in `TokenCacheService` under concurrent mesh token fetch on a
  cold cache. No pre-prod signal. 03:00 on-call page.
- **Recommendation.** Add a `MeshFederationLoadScenario` with two
  gateway instances (A and B) + a shared Redis + a WireMock IdP, and
  assert: mesh-token cache hit rate >95% after warmup, failover p99
  latency < baseline p99 + 50ms.
- **Effort.** M.

### F-033 — Greenfield copy-paste drift from jumper

- **Observation.** ADR-001 §"Lift the mature parts of jumper into
  versioned modules" lists 14 files to port. Each port is a fresh copy;
  jumper stays alive during migration (ADR-004 Phase 4 keeps jumper
  pods warm for 30 days per zone). During the 16-week rollout, jumper
  will continue to receive patches — and those patches will need to
  land in both places until EOL.
- **Risk.** A security fix lands in jumper but not in gateway-core, or
  vice-versa. The two stacks drift silently.
- **Scenario it breaks.** A CVE is published in a jumper dependency. We
  patch jumper first (because that's where the CI runs). Three weeks
  later gateway-core still has the vulnerable version. A zone that's
  half-migrated (canary on gateway-core, fallback on jumper) is vulnerable
  on half its pods.
- **Recommendation.** Extract the common parts to a library JAR
  (`gateway-common`) both stacks depend on. Even if "lift" is a
  copy-paste per ADR-001, flip the decision and publish a
  `gateway-common` library during migration to serialize fixes. Retire
  the library when jumper EOLs.
- **Effort.** L.

### F-034 — `core-controller` as a single-leader pod is a bottleneck at 10^3+ routes

- **Observation.** ADR-002 §"Controller pattern" has a single leader
  doing informer watches, reference resolution, snapshot building,
  push, and status updates. At the scale the plan mentions ("thousands
  of routes"), the snapshot build becomes O(routes × consumers × policies)
  per change.
- **Risk.** A CRD churn storm (e.g., operator applies 500 route CRs in
  a GitOps sync) blocks reconciliation for minutes; pods don't get
  their updates; stale routes serve old upstream URLs.
- **Scenario it breaks.** ArgoCD applies 500 `GatewayRoute` objects in
  a sync. The controller re-builds a snapshot for every watch event =
  500 full rebuilds. Each rebuild is O(N) in total-CR-count. Rebuild
  latency climbs to seconds; the push queue backs up; data planes see
  delays between ack'd-on-api-server and loaded-in-memory.
- **Recommendation.** Debounce: coalesce watch events in a 200ms
  window; build one snapshot per window regardless of event count.
  Pair with a per-zone build (already implied by
  `SnapshotBuilder.buildForZone(zone)`) so churn on zone A doesn't
  rebuild zone B. Add a `controller.snapshot.build.latency` metric.
- **Effort.** M.

### F-035 — `GatewayZone.failover` is a single pointer (no DAG)

- **Observation.** ADR-003 §1 says `GatewayZone.failover` is "an
  optional ref to another `GatewayZone` used as the failover target".
  Single pointer.
- **Risk.** What if zone B (the failover for A) is itself down? We
  have no transitive resolution.
- **Scenario it breaks.** Zone A → Zone B failover configured. Zone B
  is down. Failover chain terminates. Zone C exists and could have
  served the traffic, but no config path expresses this.
- **Recommendation.** Either `spec.failover: [zoneB, zoneC]` (ordered
  list) or keep single-pointer but traverse via
  `zoneB.spec.failover` transitively (with cycle detection). Document
  in ADR-003 that ordering matters and cycles are detected at admission
  time.
- **Effort.** S.

### F-036 — No chaos-testing for the documented risk mitigations

- **Observation.** ADR-004's risk table (`ADR-004-migration-path.md:157`)
  lists "Rate-limit implementation behaves differently under burst",
  "Mesh incompatibility discovered mid-rollout", "Controller outage
  during rollout" — each with a claimed mitigation. None of these
  mitigations has a corresponding automated test. Load scenarios in
  `agent-a799036c` exercise latency/spike/backpressure on a healthy
  dependency graph only.
- **Risk.** The mitigations are claims, not tested claims. ADR-004
  uses them to justify shipping in 16 weeks.
- **Scenario it breaks.** Production incident at 02:00. The mitigation
  "fail-open on rate-limiter when Redis is down" (implicitly from
  F-021) has never been tested because `redis stop` was never part of
  any load-test flow. The on-call reads the ADR, expects fail-open,
  discovers it's actually fail-closed (or vice versa), and takes 20
  extra minutes to form a hypothesis.
- **Recommendation.** Three chaos scenarios, all using Toxiproxy
  sidecars (TestContainers-friendly):
  1. `RedisOutageScenario` — asserts rate-limiter and zone-health
     behave per the declared `onRedisFailure` policy (F-021) for 60s.
  2. `IdpOutageScenario` — asserts circuit breaker opens on
     outbound-auth within 10s (F-010) and that p99 for non-auth
     routes stays within 1.1× baseline.
  3. `ControllerOutageScenario` — kills the control plane for 2 min;
     asserts data plane serves from last-known-good snapshot (F-025)
     and readiness stays true for in-zone routes, false for mesh routes
     until the controller recovers.
  Gate the ADR-004 migration timeline on these three passing.
- **Effort.** M.

---

## 3. Proposed Counter-ADRs

These are architecture-level decisions that rise out of the findings. Each
block below is a draft ADR to be copy-pasted into `docs/adr/` once agreed.
They live inline here to keep the PR scope tight.

### 3.1 Proposed Counter-ADR ADR-005 — Data-plane subscription model (pull-based gRPC)

- **Status**: Proposed
- **Date**: 2026-04-20
- **Supersedes**: ADR-002 §"Data-plane push protocol"

**Context.** ADR-002 declares a push model with mTLS gRPC, but the
landed `DataPlanePushService` is HTTP POST with no TLS, no ack flow
control, no delta, no discovery (F-001, F-002). The helm chart wires a
pull-style `admin-status-url` (F-007). The three modules disagree.

**Decision.** The data plane *pulls* from the control plane via a
long-lived gRPC bidirectional stream.

- Data plane opens `ConfigService/Subscribe(SubscribeRequest{zone, podId})`
  on startup.
- Control plane responds with a stream of `ConfigSnapshot`s (full on
  first send after (re)connect, deltas thereafter).
- Data plane ack's with `SubscribeRequest{ackVersion}` on the same
  stream (bidi).
- mTLS required; the data plane trusts the control plane's SPIFFE-style
  workload cert (or a pinned CA bundle in the bootstrap Secret).
- Snapshots are signed by the control plane's private key (see §3.2); the
  data plane verifies before applying.

**Rationale.** Discovery is free (the data plane knows where the
controller is; the reverse requires informers or k8s-client deps on the
controller). HA is trivial (followers reject the `Subscribe` until they
win leader election). Scaling out (new data-plane pod) is "just open a
stream". Pull aligns with how Envoy xDS works, so the mental model
transfers.

**Consequences.** Controller needs to run a gRPC server
(`grpc-spring-boot-starter` adds ~8 MB; proto already belongs in
`core-crd-api`). HTTP POST push goes away; `DataPlanePushService` becomes
`DataPlaneStreamHub` with a per-stream `Sinks.Many<Snapshot>`. Helm chart
loses `control-plane.admin-status-url` in favour of
`control-plane.subscription.endpoint` and keeps `admin-status.url`
strictly for read-only queries.

---

### 3.2 Proposed Counter-ADR ADR-006 — Secret material boundary

- **Status**: Proposed
- **Date**: 2026-04-20
- **Addresses**: F-003, F-004, F-024, F-027

**Context.** `GatewayCredential` today has `secretRef` (correct) but no
anti-inline guard (F-003) and no rotation primitive (F-004). The
migration tool emits inline `spec.data.password` (F-027). Snapshots are
unsigned (F-024). All together, secret material has no defined trust
boundary.

**Decision.**

1. `GatewayCredential.spec` admits *only* `type`, `secretRefs[]` (plural,
   for rotation), `issuer`, `jwksUri`, `scopes`, and metadata. All other
   fields are rejected by CRD validation (`additionalProperties: false`
   on the spec subtree) and the migration tool has a CI gate that
   rejects any emission of forbidden fields.
2. Secrets live in Kubernetes `Secret` objects, labeled
   `gateway-core.telekom.de/credential=<name>`. The controller's RBAC
   permits reading only labeled Secrets; the data plane's RBAC permits
   reading only Secrets referenced from a snapshot it has already
   verified.
3. Snapshots from controller to data plane are signed with the
   controller's Ed25519 private key (mounted from a dedicated Secret).
   Data plane verifies with a pinned public key baked into its image or
   mounted from a bootstrap Secret. Unsigned snapshots are rejected.
4. Rotation overlap: a credential may carry N secretRefs. Inbound auth
   accepts any match; after rotation the old ref is removed.

**Consequences.** Secret-rotation playbook is one additional `secretRefs`
entry → grace → remove old. etcd sees only references; backups are
safer; GitOps repos can version `GatewayCredential` objects without
carrying secret material. The data plane's Kubernetes footprint is
"GET on labeled Secrets in the credential namespace" and nothing more.

---

### 3.3 Proposed Counter-ADR ADR-007 — Filter pipeline ordering

- **Status**: Proposed
- **Date**: 2026-04-20
- **Addresses**: F-009, F-010

**Context.** Plan fixes ordering at `REQUEST_VALIDATION=100,
INBOUND=200, RATE_LIMITER=300, POLICY=400, DISCOVERY=500, OUTBOUND=600,
RESILIENCE=700, MESH=800, UPSTREAM=900`. The rate-limiter / policy swap
(F-009) and circuit-breaker placement (F-010) are wrong.

**Decision.** New default:

| Order | Stage | Why here |
| --- | --- | --- |
| 100 | REQUEST_VALIDATION | Cheap, local; reject malformed early. |
| 200 | INBOUND_AUTH | Establishes the principal. |
| 300 | POLICY (local) | Cheap CPU check; rejects most attack traffic. |
| 400 | RATE_LIMIT (Redis) | Expensive remote; only runs for principals that passed policy. |
| 500 | DISCOVERY | Resolves upstream. |
| 550 | CB_OUTBOUND_AUTH | Protects against IdP degradation. |
| 600 | OUTBOUND_AUTH | Mint / exchange tokens. |
| 650 | CB_UPSTREAM | Protects against upstream failure. |
| 700 | MESH | Cross-zone hop if applicable. |
| 900 | UPSTREAM | Forward. |

**Rationale.** Two rules: (1) cheap-before-expensive when they produce
the same decision; (2) each circuit breaker must wrap the thing it
protects, not something later. This is the order jumper implicitly
uses today (`RequestFilter` is local-only, then `UpstreamOAuthFilter`
does the IdP call), we just make it explicit and give CBs their proper
scope.

**Consequences.** POLICY runs before it can see rate-limit counters
(intentional — rate-limit is never an input to policy). Routes that
need a different order (public health endpoints, maintenance-mode
switches) declare an override in
`GatewayRoute.spec.filters.orderOverrides`.

---

### 3.4 Proposed Counter-ADR ADR-008 — Policy DSL (CEL default, Rego optional)

- **Status**: Proposed
- **Date**: 2026-04-20
- **Addresses**: F-015

**Context.** The policy-engine worktree ships SpEL and Rego. SpEL is a
sandbox hazard and is inconsistent with the CRD enum
(`gatewaypolicy.*.yaml:159` permits only `opa|cel`). Rego is powerful
but has a runtime cost and a new tooling surface for operators.

**Decision.**

1. CEL (Common Expression Language) is the default DSL for
   `GatewayPolicy type=policyEngine`. It is deterministic,
   bounded-time, no reflection, and is the Gateway API standard. Use
   `cel-java`.
2. Rego/OPA is the escape hatch for power policies that cannot be
   expressed in CEL (e.g. policies that consume external data). It
   ships behind an opt-in `GatewayPolicy.spec.settings.policyEngine.engine: opa`
   and requires explicit controller allow-list.
3. SpEL is removed. `SpelPolicyEvaluator.java` and all its tests are
   deleted.

**Rationale.** CEL is sandbox-by-design. Rego is a deliberate
complexity trade. SpEL's value proposition ("Spring devs already know
it") doesn't survive the sandbox-escape risk.

**Consequences.** The policy-engine worktree loses ~200 LOC and two
Cucumber features, gains one CEL feature (~100 LOC). Operators learn
CEL once (small syntax) instead of choosing between three DSLs.

---

### 3.5 Proposed Counter-ADR ADR-009 — Migration tool output validation gate

- **Status**: Proposed
- **Date**: 2026-04-20
- **Addresses**: F-026, F-027, F-028, F-029

**Context.** The migration tool's output is currently not validated
against the real CRDs; it fails on almost every field-name check
(F-026) and is missing half of Kong's plugin surface (F-027). ADR-004's
migration timeline is gated on the tool working.

**Decision.**

1. Every PR to `core-migration-tool` runs a CI job that spins up a
   kind cluster, applies the CRDs from `api-crds`, and runs every
   fixture through `kubectl apply --dry-run=server -f -
   --validate=true`. Any emission that fails is a hard error.
2. The tool supports, at minimum, these Kong plugins (mapping them to
   the obvious CRD target):
   - `jwt` → `GatewayCredential type=jwt` + a `GatewayConsumer`
     linking to it.
   - `key-auth` → `GatewayCredential type=apikey`.
   - `basic-auth` → `GatewayCredential type=basic`.
   - `rate-limiting` → `GatewayPolicy type=ratelimit`.
   - `cors` → `GatewayPolicy type=cors`.
   - `request-validator` → `GatewayPolicy type=requestValidation`.
   - `ip-restriction` → `GatewayPolicy type=policyEngine engine=cel`
     with a translated CIDR predicate.
   - `acl` → skipped explicitly with a rationale in the unmigrated
     report.
3. The tool also reads `jumper application.yml` + Helm values and emits
   `GatewayZone` + `GatewayMeshPeer`, per ADR-004.
4. Output is byte-for-byte idempotent (guaranteed by a Cucumber test).

**Consequences.** The tool ships with enough plugin coverage to meet
ADR-004's `<0.1% divergence` target. Schema drift is caught the
moment it happens instead of in zone-owner review.

---

## 4. What the plan got right

A credible critique acknowledges strong decisions; this section is
short on purpose (the rest of the document is not short).

- **Wire compatibility for the mesh hop (ADR-003).** Preserving the
  RS256 claim set and the Redis channel name means zones can migrate
  independently; that's the difference between a 4-month rollout and a
  4-hour outage.
- **Fabric8 + informers for the control plane.** The right library
  for the job; there is no gain from writing to `client-go` via grpc
  or reinventing the informer pattern.
- **Splitting data plane and control plane.** Hot path isolation from
  control-plane bugs is the single best architectural call in the
  plan; it pays for itself on the first controller outage.
- **Per-module Cucumber suites.** Each module having its own BDD
  suite means isolated PRs can land without a monolithic test gate.
  This is how jumper ships today and it works.
- **Lifting `TlsHardeningConfiguration` and `NettyMetricsConfig`
  verbatim.** These are the two most recent wins in jumper (Netty
  metrics is PR #102, commit `b3b176f`; TLS hardening has been
  battle-tested for two quarters). Not rewriting them is correct.
- **Gateway API–style CRDs (ADR-002).** Aligns with
  `HTTPRoute`/`parentRefs` idioms that the Kubernetes ecosystem already
  speaks; operators' muscle memory transfers.
- **20-unit work breakdown.** The orthogonality between units is
  mostly clean; the plan identifies the dependencies honestly.
- **`core-bom`.** A BOM module is the right answer for multi-module
  version pinning; downstream consumers get one dependency.
- **Multi-module parallelization via git worktrees.** Pragmatic: each
  worker lands an isolated PR; coordinator merges; no giant monorepo
  merge conflicts.

---

## 5. Open questions for the user

The following are decisions where the right call needs human
judgement (or at least a conversation), not more analysis:

1. **Push vs pull for the config channel (F-001, F-002, §3.1).** My
   recommendation is pull; it simplifies discovery and HA. But if the
   product requirement is "controller observes which data-plane pods
   ack'd a snapshot at the cluster level, not just per-stream", push
   keeps more state in the controller. Which is the operational
   priority?

2. **Fleet-wide greenfield vs. in-place evolution of jumper (F-033).**
   The user confirmed "fresh greenfield project". Given that Phase 4
   of ADR-004 keeps jumper alive for ~30 days per zone — and that the
   fleet migration is ~16 weeks — we're committing to maintain two
   stacks in parallel for ~5 months minimum. An in-place evolution of
   jumper (add Kong-replacing modules to jumper, deprecate Kong, then
   rename) is arguably 30% less code and no parallel-maintenance tax.
   Is the "fresh project" decision about technical clarity, GitHub
   history, or a political/org reason? The answer changes whether
   F-033's common-library recommendation is even relevant.

3. **Policy DSL (F-015, §3.4).** CEL is the conservative, Gateway-API-aligned
   choice. Rego gives real power but bolts an OPA runtime onto every
   data-plane pod (memory, CVE surface, operator learning). Is there
   an actual use case for Rego's power, or is the ability to hit an
   external OPA sidecar enough?

4. **Fail-open vs fail-closed on rate-limiter (F-021).** This is a
   product decision, not a technical one. What does the platform team
   prefer when Redis is down: "better to serve wrong than not at all"
   (fail-open, current practice at most CDNs) or "better to 429 than
   overload" (fail-closed, common for abuse-sensitive platforms)?

5. **Should `GatewayPolicy` be split by type (F-006, §counter-ADR)?**
   Splitting now is a breaking change we take before anyone installs
   the CRDs. Splitting later is a version bump and a migration. If we
   think we'll ship ≤5 policy types for the foreseeable future, the
   monolithic `GatewayPolicy` is cheaper. If we think we'll grow past
   that, splitting now is cheaper. Which is the roadmap bet?

6. **mTLS for mesh hop: zone-pinned JWKS vs. SPIFFE (F-014, ADR-003
   §Alt-1).** ADR-003 defers SPIFFE. A pinned JWKS + expected-kid
   allow-list is much cheaper and fixes 90% of the risk. Are we okay
   committing to the allow-list for 12 months and revisiting SPIFFE
   then?

7. **The `jumper_config` header compatibility path (ADR-003 §5).** We
   keep parsing this header during migration. Do we have a hard
   sunset date for when `gateway-core` stops accepting it? Without
   that date, "migration ends" is hard to enforce, and the code path
   becomes permanent tech debt.

---

## Appendix A: Citations index

All of the below are absolute paths at review time.

- Plan: `/Users/A85894249/.claude/plans/glowing-tickling-goose.md`
- Docs worktree: `.../agent-a18b8a74/gateway-core/{README.md,ARCHITECTURE.md,docs/}`
- CRD worktree: `.../agent-af88f0fb/gateway-core/api-crds/`
- Controller worktree: `.../agent-aebc5bcd/gateway-core/controller/`
- Policy-engine worktree: `.../agent-a2eae0cf/gateway-core/policy-engine/`
- Plugin-SPI worktree: `.../agent-a87e2877/gateway-core/plugin-spi/`
- Service-discovery worktree: `.../agent-ab797a2c/gateway-core/service-discovery/`
- Load-test worktree: `.../agent-a799036c/gateway-core/load-test-suite/`
- Docker worktree: `.../agent-ad881652/gateway-core/docker/`
- Migration-tool worktree: `.../agent-aec8cfba/gateway-core/migration-tool/`
- Helm-charts worktree: `.../agent-af37afe4/gateway-core/helm-charts/`
- Jumper source (for lift semantics): `/Users/A85894249/claude-code/gateway-jumper/src/`

Every finding above references the specific file and line. Where a
finding cites a jumper file, the version is the one at commit
`ae90510` (branch `main` at review time).

## Appendix B: Finding priority matrix

| ID | Severity | Stage | Effort | Counter-ADR |
| --- | --- | --- | --- | --- |
| F-001 | P0 | Pre-ship | L | §3.1 |
| F-002 | P0 | Pre-ship | M | §3.1 |
| F-003 | P0 | Pre-ship | S | §3.2 |
| F-004 | P1 | Pre-ship | M | §3.2 |
| F-005 | P1 | Pre-ship | M | — |
| F-006 | P1 | Pre-ship (breaking later) | M | — |
| F-007 | P1 | Pre-ship | S | §3.1 |
| F-008 | P0 | Pre-ship | M | — |
| F-009 | P1 | Pre-ship | S | §3.3 |
| F-010 | P1 | Pre-ship | M | §3.3 |
| F-011 | P1 | Production | M | — |
| F-012 | P1 | Production | M | — |
| F-013 | P1 | Production | M | — |
| F-014 | P1 | Pre-ship | S | — |
| F-015 | P1 | Pre-ship | S | §3.4 |
| F-016 | P2 | Production | M-L | — |
| F-017 | P1 | Production | L | — |
| F-018 | P0 | Pre-ship | S/M | — |
| F-019 | P1 | Pre-ship | S/M | — |
| F-020 | P1 | Production | M | — |
| F-021 | P0 | Production | M | — |
| F-022 | P1 | Pre-ship | M | — |
| F-023 | P1 | Pre-GA | S/M | — |
| F-024 | P0 | Production | M | §3.2 |
| F-025 | P0 | Production | M | — |
| F-026 | P0 | Migration | S/M | §3.5 |
| F-027 | P0 | Migration | M | §3.5 |
| F-028 | P0 | Migration | M | §3.5 |
| F-029 | P2 | Migration | S | §3.5 |
| F-030 | P2 | Pre-ship | S | — |
| F-031 | P2 | Production | S | — |
| F-032 | P1 | Pre-ship | M | — |
| F-033 | P1 | Pre-ship | L | — |
| F-034 | P1 | Production | M | — |
| F-035 | P2 | Production | S | — |
| F-036 | P2 | Production | M | — |

P0: ships broken if not addressed. P1: ships insecure or inefficient. P2:
durable debt, fix when convenient.
