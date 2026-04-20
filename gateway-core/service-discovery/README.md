<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: service-discovery

Kong-free replacement for jumper's `remote_api_url`-header-based upstream resolution.
Provides a small, pluggable `ServiceResolver` interface and three production adapters plus a
composite dispatcher and a Spring Cloud Gateway filter that rewrites the request URI to a real
backend endpoint.

## Resolver contract

```java
public interface ServiceResolver {
  String scheme();                                       // "k8s", "dns", "consul", ...
  Mono<List<ServiceEndpoint>> resolve(ServiceRef ref);   // non-blocking
}
```

`ServiceRef` carries `(name, namespace, port, scheme)` and can be parsed from a URI.
`ServiceEndpoint` is `(host, port, scheme, healthy, weight)`.

Contract rules:

- Implementations MUST be non-blocking. Any blocking I/O MUST use `Schedulers.boundedElastic()`.
- An empty list is a valid return and means "no currently healthy endpoints". The filter falls
  through without rewriting so upstream error handling can produce a 503, same as today.
- Resolvers SHOULD implement a local cache; call sites may invoke `resolve` on the hot path.

## Scheme mapping

| URI scheme | Resolver                      | Target                                 |
|------------|-------------------------------|----------------------------------------|
| `k8s://`   | `K8sEndpointSliceResolver`    | `discovery.k8s.io/v1 EndpointSlice`     |
| `dns://`   | `StaticDnsResolver`           | JVM DNS (`InetAddress.getAllByName`)    |
| `consul://`| `ConsulResolver` (opt-in)     | Consul `/v1/health/service/<name>`      |

Authority layout:

- `k8s://<service>.<namespace>:<port>` — `namespace` is split from the host.
- `dns://<host>:<port>` — port defaults to 80 if absent.
- `consul://<service>` — service name only; port comes from Consul.

## Filter order

```
ServiceDiscoveryFilter (SERVICE_DISCOVERY = 500)
  └─ runs after route matching
  └─ runs before the Netty routing filter (order 10_000+)
```

On every request whose target URI uses a discovery scheme, the filter:

1. Parses the URI into a `ServiceRef`.
2. Calls `CompositeResolver.resolve(ref)`.
3. Picks one endpoint via `WeightedRoundRobin` (weighted random, ported from jumper's
   `util/LoadBalancingUtil.java`).
4. Rewrites `ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR` to the resolved `scheme://host:port`
   preserving path and query.

If no healthy endpoint is available the request proceeds unmodified; downstream routing will fail
with the normal 503 behaviour.

## CRD hook: `GatewayRoute.spec.upstreamRef`

Teams running on Kubernetes express upstreams through a CRD rather than literal URIs:

```yaml
apiVersion: gateway.telekom.io/v1
kind: GatewayRoute
metadata:
  name: orders
spec:
  upstreamRef:
    # One of: k8s | dns | consul
    scheme: k8s
    name: orders
    namespace: prod
    port: 8080
```

The operator (separate module) renders this into a `k8s://orders.prod:8080` target; the
`ServiceDiscoveryFilter` resolves it at request time.

## Configuration

```yaml
gateway:
  service-discovery:
    dns:
      ttl: PT30S           # duration string; PT0S disables caching
    k8s:
      enabled: false       # watches EndpointSlices in all namespaces when true
    consul:
      enabled: false
      base-url: http://localhost:8500
```

## Build & test

```bash
./mvnw -pl gateway-core/service-discovery verify
```

## E2E

```bash
cd gateway-core/service-discovery
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```

## Reference

Weighted-random algorithm is a direct port of
`src/main/java/jumper/util/LoadBalancingUtil.java` (lines 18-43) with the addition of
unhealthy-endpoint filtering.
