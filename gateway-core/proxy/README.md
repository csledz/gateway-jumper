<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core / proxy

## Status

SKELETON. Not yet implemented.

This module currently contains only package scaffolding, an empty Spring Boot
entry point, the pipeline-stage enum, a config-snapshot record, and a smoke
test. Every non-trivial method throws `UnsupportedOperationException` until the
follow-up work fleshes it in. Do not rely on any behavior from this module yet.

## Purpose

The `proxy` module is the data-plane entry point for `gateway-core`. It is the
runtime process that accepts inbound HTTP requests, routes them through a
well-defined ordered pipeline of filters (validation, authentication,
rate-limiting, policy enforcement, discovery, outbound transformation,
resilience, mesh federation, and finally upstream dispatch), and streams the
upstream response back to the caller. The module owns the Spring Cloud Gateway
wiring, the pipeline ordering contract, and the configuration-snapshot type
that other gateway-core modules read from. It is deliberately thin: business
logic for each stage lives in its sibling module (for example
`auth-inbound`, `rate-limiter`, `circuit-breaker`) and is plugged in via the
`PipelineOrderedFilter` base class.

## Pipeline stages

| Order | Stage                | Responsibility                                                                 |
| ----- | -------------------- | ------------------------------------------------------------------------------ |
| 100   | REQUEST_VALIDATION   | Reject malformed requests before any further work is done.                     |
| 200   | INBOUND              | Authenticate and identify the calling consumer from inbound credentials.       |
| 300   | RATE_LIMITER         | Enforce per-consumer and per-route request-rate quotas.                        |
| 400   | POLICY               | Apply declarative routing and transformation policies for the matched route.  |
| 500   | DISCOVERY            | Resolve the logical upstream target to a concrete service instance.           |
| 600   | OUTBOUND             | Prepare outbound credentials and headers expected by the upstream.             |
| 700   | RESILIENCE           | Wrap the upstream call in circuit breakers, retries, and timeouts.             |
| 800   | MESH                 | Apply mesh-federation concerns when the target is a peer gateway.              |
| 900   | UPSTREAM             | Dispatch the request to the resolved upstream and stream the response back.    |

## Implementation recipe

The follow-up work should land the behavior in small, narrowly-scoped PRs. Each
bullet describes what must be added; algorithms are described in prose so that
the output of this skeleton is unambiguous but not prescriptive.

- Add a filter base adapter that bridges `PipelineOrderedFilter` to the Spring
  Cloud Gateway `GlobalFilter` contract. The adapter should read `stage()` on
  the subclass, translate it to the Spring `Ordered` numeric order, and
  delegate the reactive exchange to a protected template method that the
  subclass overrides. Keep the adapter free of stage-specific logic.
- Add a pipeline-registry component that collects every `PipelineOrderedFilter`
  bean at context refresh time, groups them by stage, verifies that no two
  filters declare the same sub-order within a stage, and exposes a read-only
  view for diagnostics. The verification must fail fast at startup.
- Add a configuration loader that produces `ConfigSnapshot` instances from the
  on-disk or remote configuration source. The loader should be reactive,
  cache the current snapshot, and publish a change event whenever a new
  snapshot is accepted. Do not hot-swap individual fields; always replace the
  whole snapshot atomically.
- Add the actual filter implementations in their sibling modules. Each
  sibling module depends on `proxy` and contributes one or more
  `PipelineOrderedFilter` subclasses as Spring beans. The `proxy` module
  itself must not depend on the sibling modules.
- Add a Cucumber harness under `src/test/resources/features` once the filters
  exist. Keep the step definitions thin: they should drive the running
  gateway through its public HTTP surface, not reach into internals.

## Verification

Local verification uses docker-compose to bring up the supporting services
(redis, the mock upstream, and the mock identity provider declared in the
repository-root `docker-compose.yml`) and then runs the full Maven verify
lifecycle.

```sh
docker-compose up -d
./mvnw -pl gateway-core/proxy verify
docker-compose down
```

For the skeleton, only `./mvnw -pl gateway-core/proxy clean compile` is
expected to pass. The smoke test will also pass once the Spring context can
start with the empty configuration shipped in `application.yml`.
