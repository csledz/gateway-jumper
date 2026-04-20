<!--
SPDX-FileCopyrightText: 2023 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: circuit-breaker

Per-route resilience for the Kong-free `gateway-core`, powered by
[Resilience4j](https://resilience4j.readme.io/): circuit breaker, bulkhead, and
retry with exponential backoff. Unlike the legacy Jumper global retry, every
route carries its own policy so a misbehaving backend cannot drag down its
neighbours.

## Policy model

```java
record ResiliencePolicy(CbConfig cb, BhConfig bulkhead, RetryConfig retry)
record CbConfig(float failureRateThreshold, int slidingWindowSize,
                int minimumNumberOfCalls, Duration waitDurationInOpenState,
                int permittedCallsInHalfOpenState)
record BhConfig(int maxConcurrentCalls, Duration maxWaitDuration)
record RetryConfig(int maxAttempts, Duration initialBackoff,
                   double backoffMultiplier, int[] retryOnStatuses,
                   boolean retryNonIdempotent)
```

Register a policy per route at startup or dynamically:

```java
registry.register("my-route",
    new ResiliencePolicy(
        new CbConfig(50f, 10, 10, Duration.ofSeconds(30), 3),
        new BhConfig(32, Duration.ZERO),
        new RetryConfig(2, Duration.ofMillis(100), 2.0,
                        new int[] {502, 503, 504}, false)));
```

Defaults from `ResilienceDefaults`:

| Setting                          | Default |
|----------------------------------|---------|
| Failure rate threshold           | 50%     |
| Sliding window size              | 10      |
| Wait duration in OPEN            | 30s     |
| Permitted calls in HALF_OPEN     | 3       |
| Max concurrent calls (bulkhead)  | 64      |
| Max retry attempts               | 2       |
| Initial backoff                  | 100ms   |
| Backoff multiplier               | 2.0     |
| Retry-on statuses                | 502, 503, 504 |

## Filter order

`ResilienceFilter` is a `GlobalFilter` ordered at `CIRCUIT_BREAKER = 700`. It
sits after authentication/rewrites but before the routing filter so it can
protect the downstream call. The effective operator stack (outermost first)
is:

```
Bulkhead  ->  CircuitBreaker  ->  Retry  ->  chain.filter(exchange)
```

### HTTP status mapping

| Trigger                              | Response |
|--------------------------------------|----------|
| Circuit is `OPEN` / `CallNotPermitted` | `503 Service Unavailable` |
| Bulkhead is full                     | `429 Too Many Requests` |
| Retries exhausted                    | `502 Bad Gateway` |
| Downstream 5xx (no retry applicable) | Pass through |

Retries only apply to idempotent methods (`GET`, `HEAD`, `OPTIONS`) unless
`retryNonIdempotent = true`.

## Metrics

All Resilience4j meters are published via Micrometer and exposed on
`/actuator/prometheus`:

- `resilience4j_circuitbreaker_state{name=<routeId>, state=closed|open|half_open}`
- `resilience4j_circuitbreaker_calls{name=<routeId>, kind=successful|failed|not_permitted}`
- `resilience4j_circuitbreaker_failure_rate{name=<routeId>}`
- `resilience4j_bulkhead_available_concurrent_calls{name=<routeId>}`
- `resilience4j_bulkhead_max_allowed_concurrent_calls{name=<routeId>}`

The actuator endpoints `circuitbreakers` and `circuitbreakerevents` are also
enabled for runtime inspection.

## Building & testing

```
./mvnw -pl gateway-core/circuit-breaker -am verify
```

### End-to-end smoke

```
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```

Cucumber + WireMock scenarios live under
`src/test/resources/features/` with glue in
`io.telekom.gateway.circuit_breaker.steps.*`.
