<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core/helm-charts

Helm charts that deploy the Kong-free `gateway-core` on Kubernetes.

This module ships two charts and one example umbrella chart:

| Chart                                      | Purpose                                                                                  |
|--------------------------------------------|------------------------------------------------------------------------------------------|
| `control-plane/`                           | The controller (watches `GatewayRoute` / `GatewayZone` / `GatewayMeshPeer` CRDs) plus the admin-status-api sidecar. |
| `data-plane/`                              | The Spring Cloud Gateway proxy. One release per zone.                                    |
| `examples/two-zone-mesh/`                  | Umbrella chart: Zone A + Zone B + Redis + demo CRs, wired into a two-zone federated mesh. |

No dependency on sibling `gateway-core/*` modules (image references only);
no dependency on the legacy `gateway-kong-charts`.

## Install

### Control plane

```bash
helm install gateway-core-cp ./control-plane \
  --namespace gateway-core --create-namespace \
  --set image.repository=<your-registry>/control-plane \
  --set image.tag=0.1.0
```

The release creates a `ServiceAccount`, a `ClusterRole` + binding with
read-only access to the gateway-core CRDs, a leader-election `Lease`
permission, plus the `Deployment`, `Service`, `ServiceMonitor` (optional),
`NetworkPolicy` (optional), `PodDisruptionBudget`, and `HorizontalPodAutoscaler`.

### Data plane

One release per logical zone. Minimum required values:

```bash
helm install gateway-zone-a ./data-plane \
  --namespace gateway-core \
  --set zone=zone-a \
  --set realm=default \
  --set issuer-url=https://iam-zone-a.example.com/realms/default \
  --set redis.endpoint=redis-master.gateway-core.svc.cluster.local \
  --set keypair.existing-secret=gateway-zone-a-keypair \
  --set mesh.enabled=true \
  --set 'mesh.peers[0].name=zone-b' \
  --set 'mesh.peers[0].zone=zone-b' \
  --set 'mesh.peers[0].endpoint=https://gateway-zone-b.example.com'
```

For inline key material (dev only):

```bash
helm install gateway-zone-a ./data-plane \
  --set-file keypair.signing-key=./dev-signing.key \
  --set-file keypair.signing-certificate=./dev-signing.crt \
  ...
```

### Two-zone mesh example

```bash
cd examples/two-zone-mesh
helm dependency update
helm install demo . \
  --namespace gateway-core --create-namespace
```

The umbrella wires Zone A and Zone B to a shared in-cluster Redis, installs
one control plane, and creates demo `GatewayRoute` / `GatewayZone` /
`GatewayMeshPeer` CRs so the controller has something to reconcile on the
first start.

## Values reference

### control-plane

| Key                                             | Default                                      | Description                                                |
|-------------------------------------------------|----------------------------------------------|------------------------------------------------------------|
| `replica-count`                                 | `2`                                          | Static replicas when HPA is disabled.                      |
| `image.repository` / `.tag` / `.pull-policy`    | see `values.yaml`                            | Controller + admin-status-api image coords.                |
| `service-account.create`                        | `true`                                       | Create a dedicated SA.                                     |
| `rbac.create`                                   | `true`                                       | Install the ClusterRole + ClusterRoleBinding.              |
| `rbac.extra-rules`                              | `[]`                                         | Append extra RBAC rules.                                   |
| `controller.watch-namespaces`                   | `[]` (cluster-wide)                          | Namespaces the controller reconciles.                      |
| `controller.leader-election.enabled`            | `true`                                       | Coordinate multiple replicas via `Lease`.                  |
| `controller.reconcile-interval`                 | `30s`                                        | Default reconcile loop.                                    |
| `admin-status-api.enabled`                      | `true`                                       | Run the sidecar that serves admin status.                  |
| `admin-status-api.port`                         | `8089`                                       | Sidecar container port.                                    |
| `service-monitor.enabled`                       | `false`                                      | Prometheus Operator `ServiceMonitor`.                      |
| `network-policy.enabled`                        | `false`                                      | Restrict inbound traffic.                                  |
| `hpa.*`                                         | disabled                                     | Horizontal Pod Autoscaler v2.                              |
| `pdb.enabled`, `pdb.min-available`              | `true`, `1`                                  | Pod Disruption Budget.                                     |

### data-plane

| Key                                             | Default                                      | Description                                                |
|-------------------------------------------------|----------------------------------------------|------------------------------------------------------------|
| `zone`                                          | *(required)*                                 | Logical zone name (DNS label).                             |
| `realm`                                         | `default`                                    | Identity realm.                                            |
| `issuer-url`                                    | *(empty)*                                    | OAuth2 issuer URL.                                         |
| `redis.endpoint`                                | *(required in practice)*                     | Redis hostname.                                            |
| `redis.existing-secret` / `redis.password-key`  | *(empty)*                                    | Where the Redis password lives.                            |
| `keypair.existing-secret`                       | *(empty)*                                    | If set, reuse an existing keypair Secret.                  |
| `keypair.signing-key` / `keypair.signing-certificate` | *(empty)*                              | Inline PEM material (prefer `--set-file`).                 |
| `mesh.enabled`                                  | `false`                                      | Enable zone federation.                                    |
| `mesh.peers[]`                                  | `[]`                                         | Per-peer `name`, `zone`, `endpoint`, `issuer-url`, `jwks-url`. |
| `control-plane.admin-status-url`                | *(empty)*                                    | Where the proxy fetches route updates.                     |
| `spring-extra`                                  | `{}`                                         | Appended to `application.yml` under `spring:` (camelCase). |
| `gateway-extra.default-filters` / `.timeouts`   | `[]`, `2s` / `30s`                           | Global Spring Cloud Gateway settings.                      |
| `service-monitor.enabled`                       | `false`                                      | Prometheus Operator `ServiceMonitor`.                      |
| `network-policy.enabled`                        | `false`                                      | Restrict inbound/outbound.                                 |
| `hpa.*`                                         | enabled (3-10 replicas, CPU 70 %)            | Horizontal Pod Autoscaler v2.                              |
| `pdb.enabled`, `pdb.min-available`              | `true`, `2`                                  | Pod Disruption Budget.                                     |

Key-naming rule:
**Kebab-case at the chart boundary, camelCase in Spring config.** The
ConfigMap template performs the translation — the proxy sees `issuerUri`,
`adminStatusUrl`, `signingKey`, etc.

## Two-zone mesh walkthrough

1. `examples/two-zone-mesh/values.yaml` declares `zoneA` and `zoneB` subcharts
   (both aliases of `gateway-core/data-plane`) plus a shared Redis.
2. Each zone's `mesh.peers[]` references the other zone's public endpoint,
   IAM issuer, and JWKS URL, so outbound tokens from Zone A are trusted at
   Zone B's ingress and vice versa.
3. The umbrella's `templates/demo-gateway-crs.yaml` renders:
   - Two `GatewayZone` objects (one per zone, pointing at the data-plane Service).
   - Two `GatewayMeshPeer` objects forming the bidirectional federation.
   - Two demo `GatewayRoute` objects (`/echo` in each zone) so the controller
     immediately has work to reconcile.
4. `values.schema.json` on the `data-plane` chart enforces the mesh peer
   structure, so a malformed `mesh.peers[]` entry fails `helm lint` before
   it ever reaches the cluster.

## Build / verify

```bash
./mvnw -pl gateway-core/helm-charts verify
```

**E2E recipe (infra-only unit):** the Maven `verify` phase runs
`helm lint` plus `helm template | kubectl --dry-run=client` against each
chart through `exec-maven-plugin`. There are no Cucumber/testcontainer
suites here — document as **"e2e = helm lint + helm template (infra-only unit)"**
in PRs.

**Fallback when `helm` is not installed:** the `scripts/verify-charts.sh`
entry-point falls back to `yamllint` (preferred) or a Python
`yaml.safe_load_all` syntax check on every template file. Install helm
locally for the full check:

```bash
brew install helm kubectl yamllint   # macOS
```

### Override the binary locations

```bash
./mvnw -pl gateway-core/helm-charts verify \
  -Dhelm.bin=/opt/homebrew/bin/helm \
  -Dkubectl.bin=/opt/homebrew/bin/kubectl
```

## Conventions

- SPDX headers on every file (`Apache-2.0`, copyright Deutsche Telekom AG).
- Charts use `gateway-core/<chart-name>` as the `name:` in `Chart.yaml`.
- Values keys are kebab-case at the chart boundary, camelCase inside
  Spring configuration.
- No dependency on sibling `gateway-core/*` Maven modules — this unit is
  self-contained.
