<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core / controller

Kubernetes control-plane for the Kong-free `gateway-core` landscape.

The controller runs inside the Kubernetes cluster, watches a set of custom
resources, aggregates them into per-zone configuration snapshots, and pushes
those snapshots to every data-plane pod registered with it.

## Reconciler model

Each CRD has a dedicated reconciler that:

1. Installs a fabric8 `SharedIndexInformer` on its CRD kind.
2. On `onAdd` / `onUpdate` / `onDelete`, upserts/removes the resource in the
   shared `ResourceCache`.
3. Publishes a `ConfigSnapshotEvent(zone, cause)`.

The six reconcilers are:

| CRD                | Purpose                                                |
|--------------------|--------------------------------------------------------|
| `GatewayRoute`     | A single API route exposed by the gateway              |
| `GatewayConsumer`  | Caller identity                                        |
| `GatewayCredential`| Secret or public-key attached to a consumer            |
| `GatewayZone`      | Logical zone (realms, Redis, issuer)                   |
| `GatewayMeshPeer`  | Peer zone for failover / mesh JWT forwarding           |
| `GatewayPolicy`    | Reusable policy (rate-limit, auth, header-rewrite...)  |

Every resource *except* `GatewayZone` must carry a `gateway.telekom.io/zone`
label; the label is used to partition the snapshot.

`DataPlanePushService` subscribes to `ConfigSnapshotEvent`, rebuilds the snapshot
for the affected zone via `SnapshotBuilder`, and POSTs it to every registered
data-plane URL. Retries use `spring-retry` (same pattern as jumper's
`RedisZoneHealthStatusService` / `UpstreamOAuthFilter`): fixed-then-exponential
backoff with 3 attempts; `4xx` responses are treated as permanent.

## Snapshot schema

```
POST {dataPlaneUrl}/config
Content-Type: application/json
{
  "schemaVersion": 1,
  "snapshotId":   "<uuid>",
  "generatedAt":  "<ISO-8601 instant>",
  "zone":         "<zone name>",
  "zoneSpec":     { ...GatewayZone CRD... },
  "routes":       [ ...GatewayRoute... ],
  "consumers":    [ ...GatewayConsumer... ],
  "credentials":  [ ...GatewayCredential... ],
  "meshPeers":    [ ...GatewayMeshPeer... ],
  "policies":     [ ...GatewayPolicy... ]
}
```

Response codes expected from the data plane:

- `2xx` -> snapshot accepted
- `5xx` / connection error -> retryable (exponential backoff, max 3 attempts)
- `4xx` -> permanent failure, not retried

## Configuration

`application.yml`:

| Property                         | Env var                   | Default |
|----------------------------------|---------------------------|---------|
| `server.port`                    | `CONTROLLER_PORT`         | `8090`  |
| `controller.dataplane.urls`      | `CONTROLLER_DATAPLANE_URLS` | *(empty)* |
| `kubernetes.client.namespace`    | `CONTROLLER_NAMESPACE`    | *(empty → all namespaces)* |

Management endpoints exposed: `/actuator/health`, `/actuator/prometheus`,
`/actuator/info`.

## Running locally (E2E)

From this directory:

```bash
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```

## Tests

Cucumber feature files live in `src/test/resources/features/`:

- `reconciliation.feature` — CRD change must cause a snapshot push.
- `snapshot.feature`       — multi-resource aggregation, zone isolation.
- `push-retry.feature`     — transient 5xx retried, permanent 4xx not retried.

The Spring Boot context uses fabric8's `KubernetesMockServer` so no real
cluster is required.

## NOTE on CRD types

The `api/` package inlines the CRD specs (`GatewayRoute`, `GatewayConsumer`, ...)
as simple Lombok POJOs implementing `io.fabric8.kubernetes.api.model.HasMetadata`
via `CustomResource`. This makes the controller PR **independently mergeable**.

Once the sibling `api-crds` PR lands, these types will be **superseded by the
generated fabric8 clients** and this in-lined `api/` package will be removed.
