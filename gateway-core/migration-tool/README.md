<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: migration-tool

Command-line tool for operators migrating from Kong + jumper to gateway-core.

Reads a Kong declarative-configuration file (decK YAML) and emits gateway-core
CRDs (`GatewayRoute`, `GatewayConsumer`, `GatewayCredential`, `GatewayPolicy`).
Any Kong plugin without a gateway-core mapping is listed in a diff report so
operators can see exactly what still needs manual attention.

## Build

```
./mvnw -pl gateway-core/migration-tool -am package
```

This produces a runnable fat jar at
`gateway-core/migration-tool/target/gateway-migrate.jar` plus a wrapper script
at `src/main/resources/gateway-migrate`.

## Usage

```
gateway-migrate migrate -i kong.yaml -o ./out
gateway-migrate diff     -i kong.yaml
gateway-migrate validate -i kong.yaml
```

### `migrate`

Reads `kong.yaml`, writes one YAML per CRD kind into the output directory (e.g.
`gatewayroute.yaml`, `gatewayconsumer.yaml`, `gatewaycredential.yaml`,
`gatewaypolicy.yaml`), and prints an unmigrated-plugin report to stderr. Pass
`--fail-on-unmigrated` to make the command exit with code `2` if anything was
left behind.

### `diff`

Prints the unmigrated-plugin report only, and exits with code `2` when there is
at least one unmigrated plugin (useful in CI).

### `validate`

Parses the decK YAML and reports top-level counts; exits `1` on parse errors.

## Supported resources

| Kong                                      | gateway-core CRD                       |
| ----------------------------------------- | -------------------------------------- |
| `service` + `route`                       | `GatewayRoute` (with upstream + match) |
| `consumer`                                | `GatewayConsumer`                      |
| `jwt_secret`, `keyauth`, `basicauth`      | `GatewayCredential` (one per credential) |
| plugin `rate-limiting`                    | `GatewayPolicy` type=RateLimiting      |
| plugin `cors`                             | `GatewayPolicy` type=Cors              |
| plugin `request-validator`                | `GatewayPolicy` type=RequestValidator  |

## Known unmigrated features

The diff report documents each case. Highlights:

| Kong plugin                    | Why not migrated                                                                  |
| ------------------------------ | --------------------------------------------------------------------------------- |
| `prometheus`                   | Replaced by the built-in Micrometer/Prometheus actuator in gateway-core.          |
| `ip-restriction`               | No CRD yet; handle at ingress / service mesh or open a ticket for the policy API. |
| `wasm` / `pre-function` / `post-function` | Kong-specific Wasm / Lua hooks; port to a plugin-spi filter.          |
| `bot-detection`                | No equivalent; evaluate a WAF or edge proxy.                                      |
| `acl`                          | Consumer-group ACLs not yet modelled in gateway-core CRDs.                        |

Any plugin not in the mapping table above also ends up in the unmigrated
report with a generic "review manually" message.

## Exit codes

| Code | Meaning                                                |
| ---- | ------------------------------------------------------ |
| 0    | Success                                                |
| 1    | Bad arguments / IO error / YAML parse error            |
| 2    | Unmigrated plugins present (`diff`, or `migrate --fail-on-unmigrated`) |
