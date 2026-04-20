<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: policy-engine

Embedded policy engine for the Kong-free `gateway-core`. Provides declarative,
per-route authorization decisions via a pluggable set of policy languages.

## Modules at a glance

| Package | Purpose |
| --- | --- |
| `api` | Public types: `PolicyEvaluator`, `PolicyContext`, `Policy`, `PolicyDecision` |
| `spel` | Default evaluator — SpEL expressions (`spring-expression`) with parsed-expression cache |
| `rego` | Optional Rego adapter, lazy-loaded modules. Ships with a built-in minimal Rego subset and is replaceable with `com.styra:opa-java` via the `rego` Maven profile |
| `filter` | `PolicyFilter` — `GlobalFilter` at order **400** (POLICY) |
| `registry` | In-memory `PolicyRegistry`, seeded from the controller snapshot |

## Policy model

A `Policy` is `(name, language, source)`. The `source` depends on the language:

* `SPEL` — a Spring Expression Language expression. Evaluated against a
  `PolicyContext` root object. Must return either a `Boolean` or a `Map`
  of the form `{allowed: bool, reason: string?, obligations: map?}`.
* `REGO` — Rego module text. A rule named `allow` is inspected. With the
  built-in subset: `default allow := false` plus one or more `allow { ... }`
  blocks; each expression inside a block must be truthy. Full Rego semantics
  are available when the `rego` profile pulls `com.styra:opa-java`.

Policies are stored in the `PolicyRegistry`. The filter resolves the policy
for the current request by:

1. request attribute `gateway.policy.ref` (set by preceding filters / tests)
2. route metadata key `policy`
3. request header `X-Policy-Ref` (dev only)

If no ref is resolved, the filter passes through without evaluating.

## SpEL variable dictionary

The `PolicyContext` record is the SpEL root, so you can reference:

| Reference | Type | Description |
| --- | --- | --- |
| `principalId` | `String` | Authenticated subject (e.g. JWT `sub`) |
| `scopes` | `List<String>` | Effective authorization scopes |
| `claims` | `Map<String, Object>` | Raw JWT claims |
| `method` | `String` | HTTP method |
| `path` | `String` | Request path |
| `headers` | `Map<String, List<String>>` | Multi-valued HTTP headers |
| `hasScope('x')` | method | `scopes.contains('x')` shortcut |
| `claim('k')` | method | case-sensitive claim lookup |
| `header('X-Name')` | method | case-insensitive single-value header lookup |

Examples:

```spel
hasScope('read') and claim('tenant') == 'acme'
method == 'GET' and path.startsWith('/public/')
principalId != null and headers['X-Forwarded-For'] != null
```

## Obligation contract

Obligations are returned as part of a `PolicyDecision`; the filter applies
them **only** when `allowed == true`. Keys:

| Key | Semantics |
| --- | --- |
| `add_header:<Name>` | Adds (or replaces) the header on the outbound request. Value is stringified. |
| `log` | Emits an INFO log record: `policy obligation:log reason=<r> detail=<v>`. |

A SpEL policy can attach obligations by returning a map:

```spel
{allowed: hasScope('read'), reason: 'ok', obligations: {'add_header:X-Tenant': claim('tenant')}}
```

Future keys (`remove_header:*`, `ratelimit:*`, `audit:*`) will be added
without breaking existing policies.

## Deny contract

On a denied decision the filter short-circuits with:

* HTTP status `403 Forbidden`
* Header `X-Policy-Reason: <reason>`
* JSON body `{"error":"forbidden","reason":"<reason>"}`

## CRD mapping

Policies are surfaced to the cluster through a `GatewayPolicy` Kubernetes
resource with `spec.type: policyEngine`:

```yaml
apiVersion: gateway.telekom.de/v1alpha1
kind: GatewayPolicy
metadata:
  name: read-acme
spec:
  type: policyEngine
  language: SPEL          # or REGO
  source: |
    hasScope('read') and claim('tenant') == 'acme'
```

The controller translates each resource into a `Policy` and publishes the
snapshot via `PolicyRegistry.seed(Map<String, Policy>)`. Routes reference a
policy by name via their `metadata.policy` value.

## Build & test

```bash
./mvnw -pl . verify
```

Activate the Rego adapter (real OPA, not the built-in subset):

```bash
./mvnw -pl . -Prego verify
```

### End-to-end

From `gateway-core/policy-engine/`:

```bash
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```
