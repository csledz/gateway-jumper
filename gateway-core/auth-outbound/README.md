<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: auth-outbound

## Status

SKELETON. Only public contracts defined.

This module currently publishes two types: the `OutboundTokenStrategy` SPI and the
`OutboundAuthPolicy` record (with its `Type` enum). Strategy implementations, the
tiered credential cache, and the key loader are intentionally omitted and will land
in follow-up PRs. Do not import this module yet from downstream code; the surface
will not break, but there is nothing behind it.

## Purpose

Port jumper's five outbound-credential scenarios out of the Kong sidecar and into
a standalone gateway-core module. Jumper today owns scenario detection and
credential minting in `jumper/filter/RequestFilter.java` and the upstream
OAuth/token services; this module replaces that pipeline in the Kong-free gateway
while preserving the exact header contract described in the root `README.md`. The
five scenarios are: one-token (LMS), mesh (inter-zone), external OAuth, basic
auth, and token exchange (gateway-to-gateway).

## Strategies

| Type              | Purpose                                                                      |
|-------------------|------------------------------------------------------------------------------|
| `ONE_TOKEN`       | Reuse the incoming consumer `<jwt>` as-is for the upstream call.             |
| `MESH`            | Mint a gateway-signed `<jwt>` that carries federated identity between zones. |
| `EXTERNAL_OAUTH`  | Client-credentials exchange against a provider-owned token endpoint.        |
| `BASIC`           | Attach HTTP Basic credentials resolved from the secret reference.           |
| `TOKEN_EXCHANGE`  | RFC 8693 exchange of the inbound `<jwt>` for an upstream-scoped `<jwt>`.    |

## Implementation recipe

The following describes, in prose only, what each strategy will do once
implemented. No code lives in this module today; the strategy classes will be
added per-type in their own PRs.

### `ONE_TOKEN`

Produces an outbound `Authorization: Bearer <jwt>` by copying the inbound consumer
token unchanged. Reads nothing from `OutboundAuthPolicy` except `type`. Caches
nothing — the inbound request already carries the credential. Only useful when
the upstream trusts the same issuer as jumper's inbound filter. The strategy
will log a one-line decision record at debug level and forward; no network calls
are made.

### `MESH`

Produces an outbound `Authorization: Bearer <jwt>` where the token is
gateway-signed and asserts the forwarding zone's identity. Reads `realm`,
`environment`, and `serviceOwner` from the policy, plus the RSA signing key and
kid loaded at startup (see `## Key management`). Caches the signed token keyed by
`(realm, environment, serviceOwner, clientId)` with a TTL slightly shorter than
the token's own expiry so we never serve a stale one.

### `EXTERNAL_OAUTH`

Produces an outbound `Authorization: Bearer <jwt>` obtained by a client
credentials grant against `tokenEndpoint`. Reads `clientId`,
`clientSecretRef`, `tokenEndpoint`, and `scopes` from the policy.
`clientSecretRef` is an opaque reference resolved through the platform's secret
provider; secret material never appears in policy instances or logs. Caches the
resulting access token keyed by `(tokenEndpoint, clientId, scopes)` with the
expiry reported by the authorization server.

### `BASIC`

Produces an outbound `Authorization: Basic <encoded>` where `<encoded>` is the
Base64 of `clientId:<secret>`. Reads `clientId` and `clientSecretRef`. Caches
nothing — the header is trivially re-computable and we don't want secret-derived
values sitting in Caffeine longer than a single request.

### `TOKEN_EXCHANGE`

Produces an outbound `Authorization: Bearer <jwt>` via RFC 8693 exchange. Sends
the inbound `<jwt>` as `subject_token` to `internalTokenEndpoint` and uses the
returned access token upstream. Reads `internalTokenEndpoint`, `clientId`,
`clientSecretRef`, and `scopes`. Caches the exchanged token keyed by
`(internalTokenEndpoint, subject-token-hash, scopes)` with the expiry reported
by the internal authorization server. The subject-token-hash is a SHA-256 of
the inbound token string; we never log the raw `<jwt>`.

## Tiered cache

The credential cache has two tiers. The primary tier is an in-process Caffeine
cache sized per-instance; the secondary tier is a shared Redis store. Lookups
check Caffeine first, fall back to Redis, and only then mint a new credential
through the strategy. Writes go to both tiers.

Every cached entry stores both the credential and its absolute expiry. Reads
apply a TTL buffer (default 30 seconds) — an entry whose remaining life is under
the buffer is treated as a miss, so we never hand out a credential that will
expire mid-flight upstream. The buffer is tunable per deployment.

Concurrent misses for the same key collapse through single-flight deduplication.
The first caller holds a per-key reactive `Sinks.One`; subsequent callers
subscribe to the same sink instead of re-minting. On success all subscribers see
the newly cached credential; on failure the sink is cleared so the next request
retries cleanly.

## Key management

The mesh strategy signs with an RSA private key identified by a kid. Both the
key and the kid are loaded from the filesystem at a path supplied through
environment variables (for example `GATEWAY_MESH_KEY_PATH` and
`GATEWAY_MESH_KID`). The key loader reads once at startup, holds the parsed
material in memory, and never writes it to logs, metrics, or request attributes.
No key material — neither the private PEM nor the public JWKS — is ever baked
into the image, the repository, or this module. Key rotation is handled by
restarting the pod with a new mounted path; hot rotation is out of scope for
the first PR.

## CRD mapping

A `GatewayPolicy` Kubernetes resource maps to one `OutboundAuthPolicy` per
route. The CRD's `spec.outboundAuth` block carries: the strategy `type`; the
`clientId` and `clientSecretRef` (the latter pointing at a `Secret` in the same
namespace); the `tokenEndpoint` or `internalTokenEndpoint` depending on
strategy; the requested `scopes` list; and the `realm`, `environment`, and
`serviceOwner` triple that the mesh strategy uses to identify the calling zone.
The gateway's admission webhook rejects policies that request `MESH` without a
configured signing key, or `EXTERNAL_OAUTH` / `TOKEN_EXCHANGE` without a token
endpoint.

## Verification

From inside this module directory:

```bash
./mvnw -pl . clean compile
```

This compiles the two API types and the smoke-test bootstrap. There is no
`spring-boot:run` yet — the module has no strategy beans wired, so running it
would start an empty actuator-only process on port 8080. Once the first
strategy PR lands, `curl http://localhost:8080/actuator/health` will be the
liveness check. Secrets and tokens are injected via environment variables and
mounted files, for example:

```bash
export GATEWAY_MESH_KEY_PATH=/var/run/secrets/gateway/mesh.key
export GATEWAY_MESH_KID=...
# token used by integration tests is read from $TOKEN, never hard-coded
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/...
```
