<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: request-validation

## Status

SKELETON. Only policy records defined.

No filter classes, no schema registry, no runtime wiring have been committed yet.
The module compiles, boots an empty Spring context, and exposes two public
records that downstream stages (and future filters in this module) will consume.

## Purpose

The request-validation stage is the single place in the gateway pipeline where
untrusted client input is shaped and bounded before any routing decision is
made. It combines four concerns that naturally live together: CORS policy
enforcement, JSON Schema validation of request bodies, content-type allow-list
enforcement, and hard caps on request body size. Keeping these concerns in one
stage ensures consistent rejection semantics, a single place to emit validation
metrics, and a single extension point for operators who want to tighten or
relax the defaults per route.

## Pipeline order

This module owns pipeline stage `REQUEST_VALIDATION` with ordinal `100`. It is
the first stage executed in the gateway filter pipeline, running before
authentication, rate limiting, circuit breaking, and routing. Running first is
deliberate: a malformed or oversized request should be rejected cheaply, before
the gateway spends CPU on JWT parsing or Redis round-trips for quotas.

## CORS semantics

The CORS filter will handle two request shapes. Preflight `OPTIONS` requests
are answered synchronously by the gateway itself, without forwarding to the
upstream: the filter matches the `Origin` header against `allowedOrigins`,
computes the intersection of `Access-Control-Request-Method` with
`allowedMethods`, and echoes allowed headers back. Actual (non-preflight)
requests are forwarded unchanged on the request path, and the response path
decorates the upstream response with `Access-Control-Allow-Origin`,
`Access-Control-Expose-Headers`, and, when `allowCredentials` is true, the
`Access-Control-Allow-Credentials: true` header. Origin matching is exact
string comparison on the scheme-host-port tuple; wildcard `*` is supported
only when `allowCredentials` is false, per the Fetch standard.

## JSON Schema validation

Body validation targets JSON Schema draft-2020-12. Schemas are referenced from
the policy by URI (`schemaRef`) and resolved by a schema registry component
that the implementation recipe below will introduce. Compiled schemas are
cached by reference so each request pays only validator traversal cost, not
parsing or compilation. Violations are collected and reported to the client as
a JSON document containing a list of entries keyed by JSON Pointer into the
offending request body, paired with a short machine-readable code and a human
summary. The filter returns HTTP 400 with a problem+json payload.

## Content-type enforcement

Each route carries an allow-list of media types in `allowedContentTypes`. The
filter compares the request `Content-Type` (normalised, parameters stripped)
against the list. Requests whose media type is not in the list are rejected
with HTTP 415 and no upstream call is made. Routes that omit the allow-list
fall back to a module-wide default list, which operators can configure through
standard Spring properties.

## Size limits

Two checks run in sequence. First, a fast path inspects the `Content-Length`
header: if present and greater than `maxRequestBytes`, the request is
rejected immediately with HTTP 413, before any body bytes are read. Second,
for chunked or otherwise length-less requests, the body is streamed through a
guard operator that maintains a running byte sum; as soon as the sum exceeds
the cap the downstream is cancelled and a 413 response is written. The guard
releases buffers on cancellation so Netty direct memory is not leaked.

## CRD mapping

Operators configure this stage through the cluster-scoped `GatewayPolicy` CRD.
Two policy `type` values are relevant here: `cors` maps directly onto
`CorsPolicy`, and `requestValidation` maps onto the full `ValidationPolicy`,
carrying an embedded CORS block plus schema reference, content-type allow-list,
and size cap. Multiple policies may be attached to the same route; the
controller merges them with a documented precedence (route-scoped overrides
default-scoped).

## Implementation recipe

When this skeleton is promoted to a real module, add the following classes in
package `io.telekom.gateway.request_validation.filter` (except where noted):

- `CorsFilter.java` — WebFlux filter handling preflight and response
  decoration, constructed from a `CorsPolicy`.
- `JsonSchemaFilter.java` — streams the body, feeds it to the cached validator,
  short-circuits with problem+json on violation.
- `ContentTypeFilter.java` — cheap allow-list lookup on the `Content-Type`
  header, 415 on mismatch.
- `SizeLimitFilter.java` — `Content-Length` fast path plus the streaming
  running-sum guard.
- `JsonSchemaRegistry.java` (package `...schema`) — loads schemas by URI,
  compiles once, caches compiled validators by reference.

Each filter declares its order relative to the stage ordinal so the module is
internally deterministic, and each emits its own Micrometer counters under
`gateway.request_validation.*` for observability.

## Verification

Standard recipe, run from the module directory:

    ./mvnw -pl . clean compile
    ./mvnw -pl . test

Expected outcome for this skeleton: compilation succeeds, the empty smoke test
passes, and Spotless rewrites no files on a clean tree.
