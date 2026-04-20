<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: e2e-test-suite

Cross-zone Cucumber + TestContainers suite that walks a real HTTP request through
three simulated zones and asserts the mesh-JWT contract that ties them together.

## What it proves

Three feature files, each covering one load-bearing property of the Kong-free
gateway-core:

| Feature                              | What it locks in                                                                                                                                                                                               |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `features/cross-zone-hop.feature`    | A request entering zone A that targets an upstream in zone B reaches that upstream via a peer hop, carries a mesh-JWT with `originZone=A`, `originStargate=stargate-A.local`, `aud=zone-B`, and is signed by A. |
| `features/zone-failover.feature`     | When zone B is announced UNHEALTHY on the Redis zone-health pub/sub channel, the origin proxy fails over to zone C and appends `X-Failover-Skip-Zone: B`. Recovery (HEALTHY again) restores the primary route. |
| `features/token-caching.feature`     | A burst of 200 concurrent requests crossing the same peer hop results in at most one distinct `Authorization` header observed at the target upstream, proving the peer-IdP token cache is doing its job.      |

## How it's wired

```
                      Redis (zone-health pub/sub)
                               ^
                               |
+----------+   peer   +----------+   peer   +----------+
| zone-A   |<-------->| zone-B   |<-------->| zone-C   |
| proxy    |          | proxy    |          | proxy    |
+----------+          +----------+          +----------+
     |                     |                     |
     v                     v                     v
upstream-A           upstream-B           upstream-C
(MockServer)         (MockServer)         (MockServer)
```

- `MeshTopology` brings up **1 Redis** (via `RedisContainer`), **3 MockServer upstreams**,
  and **3 zone proxies** - all wired onto a shared TestContainers network.
- The zone proxies default to **embedded Spring Boot contexts** (`EmbeddedZoneProxy`) that
  reimplement the minimum viable slice of the mesh-JWT + zone-health + token-cache flow
  inline. Each proxy binds a fresh port on the host loopback.
- If the system property `-Dgateway-core.image=<ref>` is set, `MeshTopology` instead spins
  up a `GenericContainer` per zone from that image and talks to those. The feature files
  are identical - they only see URLs.

### Why an embedded proxy

Sibling gateway-core modules (`mesh-jwt`, `zone-health`, `token-exchange`, `snapshot-loader`,
...) are landing in parallel PRs. To keep this suite mergeable *before* those land, it ships
a tiny self-contained WebFlux app (`EmbeddedZoneProxy`) that honours the exact HTTP and
header contract the real modules will emit:

| Concern            | Reimplemented in                                                                                |
| ------------------ | ----------------------------------------------------------------------------------------------- |
| Mesh-JWT mint/verify | `MeshKeyStore` + inline `MeshHandler.handleInbound` (jjwt RS256, per-zone keypairs).          |
| Zone-health pub/sub  | `ZoneHealthBus` backed by `ReactiveStringRedisTemplate.listenTo(...)`.                         |
| Peer token cache     | `PeerTokenCache` (synchronised per-peer TTL cache; token dedup is observable at the upstream). |

Once the real modules land, `EmbeddedZoneProxy` will be deleted and `MeshTopology` will
unconditionally launch the published gateway-core image via `GenericContainer`. The feature
files and step glue are designed to survive that switch untouched.

## How to run

### Plain unit run (TestContainers manages everything)

```
cd gateway-core/e2e-test-suite
../../mvnw verify
```

### End-to-end recipe with the repo's docker-compose side services

```
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```

### Running against a published gateway-core image

```
../../mvnw verify -Dgateway-core.image=ghcr.io/telekom/gateway-core:<tag>
```

## How to extend

1. Add a new `.feature` file under `src/test/resources/features/` - the Cucumber
   runner auto-discovers it.
2. Put step classes under `src/test/java/io/telekom/gateway/e2e/steps/`. Any new class
   must be in that package (the runner's glue path) and must declare its constructor
   dependency on `World` so Cucumber-Spring wires it.
3. For new topology building blocks (e.g. a second peer upstream per zone), extend
   `MeshTopology` and expose a getter for the step layer.
4. When sibling modules land, replace `EmbeddedZoneProxy` with an image reference and
   delete the `fixtures/` Java package. The `cross-zone-hop.feature` assertions on
   `X-Origin-Zone`, `X-Origin-Stargate`, `X-Mesh-Audience` are the frozen contract
   every implementation must satisfy.

## Conventions

- Java 21, package `io.telekom.gateway.e2e.*`, SPDX headers, Spotless, Lombok `@Slf4j`.
- Async assertions go through Awaitility.
- Features are hermetic: every scenario resets the `World` via a `@Before` hook.
