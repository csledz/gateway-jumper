# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

# Multi-stage Dockerfile for the gateway-core proxy (data-plane).
# Build stage compiles only the `gateway-core/proxy` module (with `-am` to
# also build its reactor dependencies) using the repo's Maven wrapper.
# Runtime image is Google's distroless Java 21, running as the `nonroot` user.
#
# Build context: repository root (so the wrapper and sibling modules are visible).
#   docker build -t gateway-core/proxy:local \
#                -f gateway-core/docker/proxy/Dockerfile .

ARG BUILD_IMAGE=eclipse-temurin:21-jdk-alpine
ARG RUNTIME_IMAGE=gcr.io/distroless/java21-debian12:nonroot

# ---------- Build stage ----------
FROM ${BUILD_IMAGE} AS build

# Alpine's temurin image does not ship a shell-friendly env for the Maven
# wrapper. Install bash + a few essentials so `./mvnw` runs reliably.
RUN apk add --no-cache bash curl tar

WORKDIR /workspace

# Copy Maven wrapper first so the cache layer can be reused between builds.
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./

# Copy the full repo so multi-module reactor resolution works. This is a
# deliberate trade-off: simpler + more correct than hand-picking module paths
# while the gateway-core layout is still evolving.
COPY . .

# Build only the proxy module (and its required dependencies via -am).
# Skip tests here; the CI workflow runs verify separately.
RUN ./mvnw -B -pl gateway-core/proxy -am package -DskipTests

# Resolve the produced jar to a stable location for the COPY below. Excludes
# Maven's auxiliary outputs (original-, -sources, -javadoc) so only the
# executable Spring Boot fat-jar is selected.
RUN set -eux; \
    jar="$(find gateway-core/proxy/target -maxdepth 1 -name '*.jar' \
             ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
             | sort | head -n1)"; \
    test -n "$jar"; \
    cp "$jar" /workspace/app.jar

# ---------- Runtime stage ----------
FROM ${RUNTIME_IMAGE}

# Distroless `nonroot` variant already runs as UID 65532 (nonroot:nonroot).
USER nonroot

WORKDIR /app

COPY --from=build --chown=nonroot:nonroot /workspace/app.jar /app/app.jar

EXPOSE 8080

# Health: Spring Boot's actuator is exposed at /actuator/health on the same
# port. Distroless ships no shell or curl, so Docker-level HEALTHCHECK is
# intentionally NOT declared here; orchestrators (Kubernetes, Nomad) MUST
# perform HTTP probes against /actuator/health (liveness + readiness).
# See gateway-core/docker/README.md for the recommended probe configuration.
HEALTHCHECK NONE

ENTRYPOINT ["/usr/bin/java", "-jar", "/app/app.jar"]
