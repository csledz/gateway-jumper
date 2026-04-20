<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: auth-inbound

## Status

SKELETON. Only public interfaces defined.

This module currently ships the minimum surface required to let sibling
modules compile against the inbound authentication contract. All behaviour
described below is intentionally unimplemented. The public types are
`InboundAuthenticator` and the `AuthContext` record (plus its `Type` enum).
Every method throws `UnsupportedOperationException` pointing at the
Implementation section of this file.

## Purpose

The `auth-inbound` module is the single, strongly-typed entry point through
which a request acquires an identity before any routing, rate limiting, or
upstream dispatch takes place. It accepts a reactive `ServerWebExchange`,
selects the correct authenticator based on the request shape and the
`GatewayCredential` custom resource attached to the matched route, and
returns a populated `AuthContext` that downstream filters can trust without
re-parsing headers. The goal is a clean seam between transport-level
concerns (reading headers, short-circuiting failures) and policy concerns
(who is the caller, what can they do).

## Authenticator types

- **JWT** — Bearer credentials presented in the `Authorization` header.
  Validated against an issuer-specific key set obtained over HTTPS and
  refreshed on `kid` miss.
- **API-key** — An opaque credential presented in either a configured
  header or a query parameter. Resolved via a constant-time lookup against
  a hashed registry and never compared with raw string equality.
- **Basic** — RFC 7617 credentials presented in the `Authorization`
  header. Only permitted for routes whose `GatewayCredential` explicitly
  declares the Basic type; otherwise rejected at the dispatch step before
  any decoding is attempted.
- **RFC 7662 introspection** — Opaque bearer credentials that are not
  self-contained and must be submitted to an authorisation server for
  validation. Results are cached for the lifetime advertised by the
  server, bounded by a local ceiling.

## Implementation recipe

The following is the algorithm each concrete authenticator must follow
once this skeleton is fleshed out. It is described in prose to make the
cut points explicit; the skeleton deliberately ships no helpers.

### JWT

1. Read the `Authorization` header. If absent or not of the Bearer form,
   complete empty so the dispatcher can fall through to the next
   authenticator or reject the request.
2. Parse the header (protected) portion without verifying. Extract the
   `kid` and `alg`. Reject any `alg` outside the route's allow-list and
   any algorithm family that the allow-list does not name.
3. Look up the verification key for `kid` from the issuer-keyed cache. On
   a cache miss, fetch the issuer's key set, repopulate the cache with
   a bounded TTL, and retry exactly once. Persistent misses surface as an
   authentication failure.
4. Verify the signature, then validate `iss`, `aud`, `exp`, `nbf`, and
   `iat` with configurable clock skew. Reject on any failure.
5. Project the validated claims into an `AuthContext` whose `type` is
   `JWT` and whose `scopes` are derived from the claim named by the
   route configuration.
6. On failure, log at WARN the outcome category, the issuer, the `kid`,
   and the current trace identifier. Never log the raw credential.

### API-key

1. Extract the presented credential from the header or query parameter
   declared by the route.
2. Hash the presented credential with the registry's configured
   algorithm and compare the result to the stored digest using a
   constant-time comparison.
3. Load the associated principal and scopes, then build an `AuthContext`
   whose `type` is `APIKEY`.
4. Cache the digest-to-principal mapping for a bounded TTL so that the
   hot path avoids the backing store. Invalidate on key rotation events
   emitted by the admin channel.
5. On failure, log at WARN the outcome category, the truncated digest,
   and the trace identifier. Never log the presented credential.

### Basic

1. Confirm that the route's `GatewayCredential` declares the Basic type.
   If it does not, reject immediately without decoding.
2. Decode the credential portion of the `Authorization` header and split
   on the first colon. Both sides must be non-empty after decoding.
3. Resolve the identifier side against the registry, then verify the
   verifier side against the stored hash using a constant-time
   comparison.
4. Build an `AuthContext` whose `type` is `BASIC`.
5. On failure, log at WARN the outcome category, the identifier side,
   and the trace identifier. Never log the verifier side.

### RFC 7662 introspection

1. Extract the opaque credential from the `Authorization` header.
2. Look it up in the introspection cache. Cache hits short-circuit the
   remote call; misses proceed.
3. POST to the authorisation server's introspection endpoint using the
   gateway's own client credentials. Treat any non-active response as
   an authentication failure.
4. Project the response into an `AuthContext` whose `type` is
   `INTROSPECTION`, then cache for the lesser of the advertised
   remaining lifetime and a configured local ceiling.
5. On failure, log at WARN the outcome category, the issuer host, and
   the trace identifier. Never log the credential.

## CRD mapping

The selector for which authenticator runs is the `type` field of the
`GatewayCredential` custom resource attached to the matched route. The
allowed values are `jwt`, `apikey`, `basic`, and `introspection`, which
map one-to-one onto the `AuthContext.Type` enum in this module. Route
binding, refresh, and rotation are the responsibility of the controller
that reconciles `GatewayCredential` resources; this module only consumes
the resolved type at dispatch time. Routes without a bound
`GatewayCredential` bypass `auth-inbound` entirely.

## Verification

From the repository root, start the compose stack and run the Maven
build. The skeleton compiles and the smoke test loads the empty Spring
context; no authenticator is wired in yet, so no credentials of any
kind are required to run the build.

```
docker compose up -d
./mvnw -pl gateway-core/auth-inbound -am verify
```

Substitute `<token>` or `$JWT` for any example credentials you add once
the implementation lands; this module ships none.
