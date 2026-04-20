<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: admin-status-api

Read-only status API and OpenAPI spec over the live runtime state of the
Kong-free gateway-core.

> **READ-ONLY.** The CRDs in the cluster are the source of truth. All mutations
> must be performed via `kubectl` against those CRDs. This module never writes.

## Endpoints

| Method | Path                             | Purpose                                             |
| ------ | -------------------------------- | --------------------------------------------------- |
| GET    | `/admin/routes`                  | List runtime routes                                 |
| GET    | `/admin/routes/{id}`             | Fetch a single runtime route (404 when unknown)     |
| GET    | `/admin/zones`                   | List runtime zones                                  |
| GET    | `/admin/zones/{name}/health`     | Aggregate zone health snapshot                      |
| GET    | `/admin/consumers`               | List registered consumers                           |
| GET    | `/admin/consumers/{id}`          | Fetch a single consumer                             |
| GET    | `/admin/cache-stats`             | Token cache + JWKS cache + schema cache statistics  |
| GET    | `/admin/snapshot`                | Aggregate snapshot (routes + zones + consumers + caches) |
| GET    | `/admin/docs`                    | Swagger UI                                          |
| GET    | `/v3/api-docs`                   | OpenAPI 3.1 JSON document                           |

A static OpenAPI 3.1 spec lives at
[`src/main/resources/openapi/admin-api.yaml`](src/main/resources/openapi/admin-api.yaml).

## Auth model

| Property              | Default | Meaning                                   |
| --------------------- | ------- | ----------------------------------------- |
| `admin.security.mode` | `basic` | `basic` (dev: HTTP basic auth) or `mtls`  |
| `admin.security.user` | `admin` | Username for basic auth                   |
| `admin.security.password` | `admin` | Password for basic auth               |

- In **dev** (`basic`), `/admin/**` is protected by HTTP basic auth.
- In **prod** (`mtls`), `/admin/**` is protected by mutual TLS (X.509 client certs).
- `GET /actuator/health` and `GET /actuator/info` remain public for liveness probes.

Unauthenticated requests to `/admin/**` return **401 Unauthorized**.

## Running locally

```
../../mvnw -pl . spring-boot:run
```

The API comes up on port **8091** with a pre-seeded demo in-memory state, so
you can exercise every endpoint without wiring a real reader.

## Extending with a real reader

Sibling modules that observe the actual CRDs contribute a bean of type
`io.telekom.gateway.admin_status_api.service.RuntimeStateReader`. The module's
default in-memory implementation is only installed when no other
`RuntimeStateReader` bean is present, so the override is purely additive.

## End-to-end recipe

```
cd gateway-core/admin-status-api
docker-compose -f ../../docker-compose.yml up -d redis echo prometheus jaeger
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify
docker-compose -f ../../docker-compose.yml down
```
