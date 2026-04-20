<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: rate-limiter

## Status

SKELETON. Public contract only; implementation pending.

This module contains only the API records and the module layout. No limiter
engine, no WebFlux filter, and no Redis script are present yet. Subsequent
pull requests will add each of those pieces incrementally, so that every
change can be reviewed in isolation.

## Purpose

The rate limiter enforces a per-consumer, per-route, and per-API request
budget for traffic flowing through the gateway. A policy declares how many
requests are allowed inside a rolling window, a short burst allowance, and
the SpEL-style expression used to derive the key from the incoming exchange.
Upstream services see only the requests that pass the limiter; excess
requests are rejected before any downstream call is attempted, so the
limiter also protects backends from overload.

## Multi-pod correctness (MUST-HAVE)

The gateway runs as multiple replicas behind a load balancer. Per-pod
counters are not acceptable because a client that lands on two different
pods would effectively double its budget. Limits MUST be enforced in
aggregate across replicas via a shared Redis counter.

The implementation MUST perform the atomic increment-and-check in a single
server-side Lua script invoked through `EVAL` (first call) and `EVALSHA`
(subsequent calls, using the cached script hash). All read and write
operations against the counter key MUST happen inside that one script,
because only a single `EVAL` round-trip is atomic with respect to concurrent
pods; splitting the operations into multiple Redis commands would allow two
pods to both observe `count < limit` and both admit the request.

Cucumber verification for this module MUST run two proxy contexts (two
`SpringApplication` instances on distinct ports, both backed by Lombok-free
skeletons) against one Redis. The test asserts that the total count of
accepted requests across both proxies never exceeds the configured limit
for a window, for a representative mix of scopes.

## Algorithm

The algorithm is a Redis-backed sliding window log. Each key maps to a
sorted set (`ZSET`) whose members are unique request identifiers and whose
scores are the millisecond arrival timestamp. A single admission decision
performs, in order:

1. `ZREMRANGEBYSCORE key -inf (now - windowMillis)` — evict samples that
   have fallen off the trailing edge of the window.
2. `ZCARD key` — count remaining samples inside the window.
3. If `count < limit + burst`, `ZADD key now <uuid>` — record the admission.
4. `PEXPIRE key windowMillis` — bound the key's lifetime so that idle keys
   are reclaimed by Redis without an explicit sweeper.

The script returns a tuple of `(allowed, remaining, resetAtEpochMs,
retryAfterMillis)` which is mapped into `RateLimitDecision`. The
`retryAfterMillis` value is derived from the oldest sample's score plus the
window length, giving the client an accurate hint for when the next slot
becomes available. The `.lua` script itself is added in a follow-up PR to
keep this PR focused on the public contract.

## Response on deny

A denied request is answered with HTTP `429 Too Many Requests` and the
following headers:

- `Retry-After` — seconds until the next slot (integer, ceiling of
  `retryAfterMillis / 1000`).
- `X-RateLimit-Limit` — the configured `limit` value.
- `X-RateLimit-Remaining` — `0` on deny.
- `X-RateLimit-Reset` — the Unix epoch (seconds) at which the window
  resets, matching `resetAtEpochMs / 1000`.

The body is empty; the headers carry all of the rate-limit signalling so
that SDKs and caches can respond without parsing a payload.

## Failure modes

- **Redis unavailable.** If the limiter cannot reach Redis within the
  configured timeout, it fails open by default: the request is admitted,
  a `gateway_ratelimit_degraded_total` counter is incremented, and a WARN
  log line is emitted. The fail-open behaviour is configurable per
  deployment; fail-closed is available for tenants where strict enforcement
  outweighs availability.
- **Redis master failover mid-script.** If the script is interrupted by a
  replica promotion (for example during a Sentinel-triggered failover),
  the client sees a connection reset or a `READONLY` error. The limiter
  retries the call once against the new master, then falls back to the
  Redis-unavailable path (fail-open by default, with the degraded-counter
  increment).

## CRD mapping

Rate-limit policies are expressed in the platform `GatewayPolicy` custom
resource with `spec.type: ratelimit`. The `spec.config` object carries
`limit`, `windowSeconds`, `burst`, `keyExpression`, and an optional `scope`
which maps one-to-one to `RateLimitKey.Scope`. The operator reconciles each
CRD into an in-memory `RateLimitPolicy` record loaded by the gateway on
startup and on watch events.

## Implementation recipe

Follow-up PRs add, in order:

- `SlidingWindowRateLimiter.java` — the `RateLimiter` implementation that
  invokes the Lua script through `ReactiveRedisTemplate`.
- `sliding-window.lua` — the atomic script, placed under
  `src/main/resources/redis/`.
- `RateLimitFilter.java` — the WebFlux `GlobalFilter` that resolves the
  policy for an exchange, calls the limiter, writes the rate-limit headers,
  and short-circuits with `429` when denied.
- `FailModeConfig.java` — `@ConfigurationProperties` that binds
  `gateway.ratelimit.fail-mode: open|closed` and the Redis timeout.
- A multi-pod test harness that boots two Spring contexts against a single
  embedded Redis and replays a deterministic load pattern, asserting the
  aggregate count matches the policy.

## Verification

Standard recipe, run from the repo root:

```
./mvnw -pl gateway-core/rate-limiter clean compile
./mvnw -pl gateway-core/rate-limiter test
```

The skeleton compiles cleanly and the `ApplicationSmokeTest` context loads
with Redis auto-configuration excluded, confirming the module wiring is
sane before any implementation is added.
