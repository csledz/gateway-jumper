<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gatectl

`gatectl` is the operator CLI for the Kong-free `gateway-core`. It speaks to the
Kubernetes API (for the gateway CRDs) and to the `admin-status-api` (for live
health), so operators can inspect and navigate the system without learning two
separate tools.

Everything is a plain, standalone Java 21 artifact — no Spring runtime, no
network daemon. stdout is reserved for command output (pipe it into `jq`, `yq`,
grep, …); diagnostics and progress go to stderr.

## Install

The preferred path is to pull a release artifact from GitHub:

```bash
# 1. Download the latest shaded jar + bash launcher from the GH release
mkdir -p ~/.local/bin/gatectl
cd ~/.local/bin/gatectl
gh release download --repo telekom/gateway-jumper --pattern 'gatectl-*-all.jar'
gh release download --repo telekom/gateway-jumper --pattern 'gatectl' --pattern 'gatectl.bat'
chmod +x gatectl
# 2. Put the launcher on $PATH
ln -sf "$PWD/gatectl" ~/.local/bin/gatectl-bin && export PATH="$HOME/.local/bin:$PATH"
```

A local build drops the same artifacts into `gateway-core/cli/target/`:

```bash
cd gateway-core/cli
../../mvnw -pl . package
java -jar target/gatectl-0.0.0-all.jar --help
```

## Usage

```text
gatectl [OPTIONS] COMMAND [ARGS]

Commands:
  get-routes        List routes (aliases: routes)
  get-zones         List zones (aliases: zones)
  get-consumers     List consumers (aliases: consumers)
  get-mesh-peers    List mesh peers (aliases: mesh-peers, peers)
  describe-route    Describe a route by name
  describe-zone     Describe a zone (joins CRD + live admin-API health)
  describe-consumer Describe a consumer by name
  logs              Tail proxy pod logs (supports --follow and --grep)
  health            Hit admin-status-api /admin/zones/*/health, colored table
  help              Print command help
```

Global flags (inheritable by every subcommand):

| Flag | Default | Description |
| ---- | ------- | ----------- |
| `--context` | `current-context` | Kubeconfig context |
| `-n, --namespace` | `gateway-system` | Kubernetes namespace |
| `--admin-url` | `$GATECTL_ADMIN_URL` | Admin API base URL |

### Examples

```bash
# kubectl-style listing with JSON output
gatectl get-routes -A -o json | jq '.[] | select(.STATUS!="Ready")'

# Detail view with live zone health
gatectl describe-zone eu-central -n gateway-system

# Tail logs from a proxy pod, grep for errors
gatectl logs gateway-proxy-0 -f --grep 'ERROR|5\\d\\d'

# Quick health dashboard — exit 1 if anything isn't HEALTHY
gatectl health
```

## Environment variables

| Variable | Purpose |
| -------- | ------- |
| `GATECTL_ADMIN_URL` | Default admin-status-api base URL |
| `GATECTL_HOME` | Launcher: directory containing the shaded jar |
| `GATECTL_JAVA` | Launcher: path to the `java` binary |
| `GATECTL_OPTS` | Launcher: extra JVM flags (e.g. `-Xmx256m`) |
| `KUBECONFIG` | Standard kubeconfig path (respected via fabric8 autoConfigure) |

## Exit codes

| Code | Meaning |
| ---- | ------- |
| `0` | Success |
| `1` | Command error (API unreachable, resource missing, unhealthy status) |
| `2` | Usage error (bad flag, unknown subcommand) |

## Development

```bash
cd gateway-core/cli
../../mvnw -pl . verify
```

Cucumber feature files live under `src/test/resources/features/` and exercise
`GetRoutes`, `DescribeZone` and `HealthCheck` against the fabric8 mock server
and a MockServer instance standing in for the admin API. Glue code is in
`io.telekom.gateway.cli.steps`.
