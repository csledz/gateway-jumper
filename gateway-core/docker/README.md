<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: CC0-1.0
-->

# gateway-core container images

This directory hosts the container build definitions for the Kong-free
`gateway-core` stack:

| Component    | Role         | Port | Dockerfile                               |
|--------------|--------------|------|------------------------------------------|
| `controller` | control-plane| 8090 | `gateway-core/docker/controller/Dockerfile` |
| `proxy`      | data-plane   | 8080 | `gateway-core/docker/proxy/Dockerfile`      |

Both images use a two-stage build:

1. **Build stage** — `eclipse-temurin:21-jdk-alpine`. The repository's Maven
   wrapper (`./mvnw`) runs `-pl gateway-core/<module> -am package -DskipTests`
   so only the requested module and its reactor dependencies are compiled.
2. **Runtime stage** — `gcr.io/distroless/java21-debian12:nonroot`. No shell,
   no package manager, UID 65532. The fat-jar is copied in and executed via
   `java -jar /app/app.jar`.

## Build

Both Dockerfiles expect the **repository root** as the build context. That is
required so the build stage can reach `./mvnw`, `.mvn/`, and sibling modules.

```bash
# Controller (control-plane, :8090)
docker build \
  -t gateway-core/controller:local \
  -f gateway-core/docker/controller/Dockerfile \
  .

# Proxy (data-plane, :8080)
docker build \
  -t gateway-core/proxy:local \
  -f gateway-core/docker/proxy/Dockerfile \
  .
```

### Build arguments

| Arg             | Default                                            | Purpose                           |
|-----------------|----------------------------------------------------|-----------------------------------|
| `BUILD_IMAGE`   | `eclipse-temurin:21-jdk-alpine`                    | Maven/JDK image used to compile   |
| `RUNTIME_IMAGE` | `gcr.io/distroless/java21-debian12:nonroot`        | Distroless runtime image          |

Override example:

```bash
docker build \
  --build-arg RUNTIME_IMAGE=gcr.io/distroless/java21-debian12:nonroot \
  -t gateway-core/controller:local \
  -f gateway-core/docker/controller/Dockerfile \
  .
```

## Run

```bash
# Controller
docker run --rm -p 8090:8090 gateway-core/controller:local

# Proxy
docker run --rm -p 8080:8080 gateway-core/proxy:local
```

## Health checks

Distroless runtime images intentionally ship **no shell and no `curl`**, so a
Docker-level `HEALTHCHECK` cannot be implemented in the usual way. Both images
therefore set `HEALTHCHECK NONE` and rely on the orchestrator (Kubernetes,
Nomad, Docker Swarm with a custom probe) to hit the Spring Boot actuator:

```yaml
# Kubernetes example
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8090        # or 8080 for proxy
  initialDelaySeconds: 30
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8090
  initialDelaySeconds: 10
  periodSeconds: 10
```

The `/actuator/health` endpoint is exposed on the same HTTP port as the
application traffic.

## Image tagging conventions

Images published by CI follow the pattern:

```
${REGISTRY_HOST}${REGISTRY_REPO}/<component>:<tag>
```

Where `<component>` is `controller` or `proxy` and `<tag>` is derived as
follows (same rules as the existing jumper build):

| Trigger                         | Tag                                       |
|---------------------------------|-------------------------------------------|
| Git tag `v*` push               | the tag (e.g. `v1.2.3`)                   |
| Push to `main`                  | `latest`                                  |
| Other branch push               | slug of the branch name                   |
| Pull request                    | `pr-<number>-<slug>`                      |
| `workflow_dispatch` with `tag`  | the supplied string                       |

Branch builds (other than `main`) receive the `quay.expires-after=60d` label
so they are auto-pruned; `latest` and tagged releases are permanent.

Local iteration should use the `:local` tag shown above to avoid confusion
with CI-produced artefacts.

## Non-root guarantees

Both runtime images run as the distroless `nonroot` user (UID 65532). The
Spring Boot fat-jars therefore must not attempt to bind privileged ports or
write outside `/tmp` and the working directory (`/app`). If filesystem
persistence is required, mount a volume owned by UID 65532.

## Related references

- `Dockerfile` and `Dockerfile.multi-stage` at the repository root — reference
  pattern for the legacy jumper image.
- `.github/workflows/gateway-core-image.yml` — release pipeline that builds
  and pushes both images on `v*` tags.
- `.github/workflows/gateway-core-ci.yml` — PR pipeline that validates the
  Maven modules these images contain.
