<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: api-crds

CustomResourceDefinitions for the Kong-free `gateway-core` landscape built on top
of `gateway-jumper`. The CRDs describe the routing, consumer, credential, zone,
mesh-peer and policy objects that the gateway-core controller watches.

- API group: `gateway.telekom.de`
- Version:   `v1alpha1`
- Scope:     `Namespaced`

## Layout

```
api-crds/
  crds/                               # the 6 CRDs
  examples/                           # one valid example per CRD
  kustomization.yaml                  # aggregates all CRDs
  pom.xml                             # runs kubectl --dry-run in verify
  README.md
```

## Install

Using kustomize (recommended):

```bash
kubectl apply -k gateway-core/api-crds/
```

Or individually:

```bash
kubectl apply -f gateway-core/api-crds/crds/
```

Remove with:

```bash
kubectl delete -k gateway-core/api-crds/
```

## Verify locally (dry-run)

From this directory:

```bash
./mvnw -pl . verify
```

### Verify variants

- Default: `kubectl kustomize` aggregates the 6 CRDs — fully offline, no
  kubeconfig needed. Catches YAML / schema shape regressions.
- With a reachable cluster (kind, minikube, real target):

  ```bash
  ./mvnw -pl . -Pwith-cluster verify
  ```

  Runs the canonical e2e recipe: `kubectl apply --dry-run=client -f crds/ -f examples/`.
- Without kubectl at all:

  ```bash
  ./mvnw -pl . -Pno-kubectl verify
  ```

  Skips external validation; useful on minimal build images.

## CRDs at a glance

### GatewayRoute

| Field                         | Type     | Required | Notes                                              |
| ----------------------------- | -------- | -------- | -------------------------------------------------- |
| `spec.hosts[]`                | string   | yes      | Host names (SNI / Host header)                     |
| `spec.paths[]`                | string   | yes      | Path prefixes, each starts with `/`                |
| `spec.methods[]`              | enum     | no       | GET/POST/... — empty = ANY                         |
| `spec.upstreamRef.name`       | string   | yes      | Upstream service name (k8s or external)            |
| `spec.upstreamRef.namespace`  | string   | no       |                                                    |
| `spec.upstreamRef.port`       | int      | no       | 1..65535                                           |
| `spec.upstreamRef.scheme`     | enum     | no       | `http` or `https` (default `https`)                |
| `spec.upstreamRef.url`        | string   | no       | Set when upstream is external                      |
| `spec.timeouts.{connect,read,write}` | duration | no | e.g. `10s`, `60s`                                 |
| `spec.retries.attempts`       | int      | no       | 0..10                                              |
| `spec.retries.statuses[]`     | enum     | no       | BAD_GATEWAY etc.                                   |
| `spec.retries.methods[]`      | enum     | no       |                                                    |
| `spec.policyRefs[]`           | ref      | no       | GatewayPolicy names                                |
| `spec.zone`                   | string   | no       | Zone name (matches GatewayZone.spec.name)          |

### GatewayConsumer

| Field                         | Type     | Required | Notes                                              |
| ----------------------------- | -------- | -------- | -------------------------------------------------- |
| `spec.clientId`               | string   | yes      | JWT `azp` / `client_id`                            |
| `spec.displayName`            | string   | no       | Human-readable name                                |
| `spec.labels`                 | map      | no       | Free-form labels                                   |
| `spec.credentialRefs[]`       | ref      | no       | GatewayCredential names                            |

### GatewayCredential

| Field                         | Type     | Required | Notes                                              |
| ----------------------------- | -------- | -------- | -------------------------------------------------- |
| `spec.type`                   | enum     | yes      | `jwt` / `apikey` / `basic` / `oauth2-client`       |
| `spec.issuer`                 | string   | jwt/oauth2-client | JWT `iss`                                 |
| `spec.jwksUri`                | string   | jwt/oauth2-client | JWKS endpoint                             |
| `spec.secretRef.name`         | string   | apikey/basic/oauth2-client | Secret holding the value         |
| `spec.secretRef.key`          | string   | no       | Secret data key                                    |
| `spec.scopes[]`               | string   | no       | OAuth2 / JWT scopes                                |

### GatewayZone

| Field                         | Type     | Required | Notes                                              |
| ----------------------------- | -------- | -------- | -------------------------------------------------- |
| `spec.name`                   | string   | yes      | Zone id (`space`, `canis`, `aries`, ...)           |
| `spec.realm`                  | string   | yes      | Keycloak realm for mesh JWTs                       |
| `spec.stargate`               | URL      | yes      | Edge URL                                           |
| `spec.internetFacing`         | bool     | no       | Default `false`                                    |
| `spec.issuerUrl`              | URL      | yes      | JWT issuer URL                                     |
| `status.healthy`              | bool     | —        | From Redis zone-health signal                      |
| `status.lastSeen`             | datetime | —        | Last health signal timestamp                       |

### GatewayMeshPeer

| Field                         | Type     | Required | Notes                                              |
| ----------------------------- | -------- | -------- | -------------------------------------------------- |
| `spec.peerZone`               | string   | yes      | Target GatewayZone name                            |
| `spec.endpoint`               | URL      | yes      | HTTPS endpoint for cross-zone calls                |
| `spec.mtls`                   | bool     | no       | Require mTLS                                       |
| `spec.tokenEndpoint`          | URL      | yes      | OAuth2 token endpoint                              |
| `spec.clientCredentialsRef`   | ref      | yes      | GatewayCredential (oauth2-client)                  |

### GatewayPolicy

| Field                         | Type     | Required | Notes                                              |
| ----------------------------- | -------- | -------- | -------------------------------------------------- |
| `spec.type`                   | enum     | yes      | `ratelimit`, `cors`, `requestValidation`, `circuitBreaker`, `policyEngine` |
| `spec.settings`               | object   | yes      | Exactly the sub-field matching `spec.type` must be present |

`settings` (oneOf by type):

- `ratelimit`: `{ limit, window, key?, headerName? }`
- `cors`: `{ allowedOrigins[], allowedMethods[], allowedHeaders[], exposedHeaders[], allowCredentials, maxAge }`
- `requestValidation`: `{ maxBodySize, allowedContentTypes[], schemaRef{ name, namespace? } }`
- `circuitBreaker`: `{ failureThreshold, resetTimeout?, halfOpenRequests? }`
- `policyEngine`: `{ engine(opa|cel), url?, policy?, failOpen? }`

## Examples

See `examples/` for one valid object per CRD.
