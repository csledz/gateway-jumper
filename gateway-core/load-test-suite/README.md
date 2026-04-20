<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: load-test-suite

A load / bottleneck test harness for the **gateway-core data plane**. Its job is to prove that
under realistic traffic:

1. The **gateway** (not the upstream) is the bottleneck - so upstreams in the suite are
   deliberately lean.
2. When the gateway *is* the bottleneck, the bottleneck is **visible** through open-connections
   count, pool saturation, event-loop lag, backpressure, 5xx-under-load, and memory pressure.
3. A **slow upstream** surfaces how open-connection counts grow as upstream latency grows
   (Little's Law: `concurrency = arrival_rate * latency`).

## Scenarios

| Feature file | Scenario | What it proves |
| --- | --- | --- |
| `load-steady-state.feature` | `SteadyStateScenario` | Moderate steady RPS keeps p99 inside budget and never hits 5xx. |
| `load-slow-upstream-connection-growth.feature` | `SlowUpstreamConnectionGrowthScenario` | As upstream latency climbs 10 ms -> 2 s, gateway open-connections rise monotonically; pool eventually saturates. |
| `load-spike-recovery.feature` | `SpikeScenario` | A 5x spike is absorbed; post-spike p99 returns close to baseline. |
| `load-backpressure.feature` | `BackpressureScenario` | Driving the gateway past its processing rate produces visible queue-depth / pool-acquire signals. |
| `load-flaky-resilience.feature` | `ResilienceUnderLoadScenario` | A 20%-failing upstream does not turn into a 60%-failing gateway via retry amplification. |

## How to run

Local (recommended for a quick smoke test):

```
cd gateway-core/load-test-suite
../../mvnw verify
```

End-to-end, with the repo's shared docker-compose stack:

```
cd gateway-core/load-test-suite
docker-compose -f ../../docker-compose.yml up -d redis echo prometheus jaeger
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify
docker-compose -f ../../docker-compose.yml down
```

## Reports

Each Cucumber scenario writes a Markdown report to `${load.reports.dir}`
(default `target/load-reports`). Reports include the HdrHistogram-derived
percentiles (p50 / p95 / p99 / p99.9 / max), the 2xx/4xx/5xx split, and the
scenario-specific observations map. For the slow-upstream scenario that
includes the full `openConnectionsTrajectory` and the Pearson correlation
between elapsed time (the latency ramp proxy) and the sampled open-connection
count.

## Interpreting the signals

- **Open connections (`reactor_netty_connection_provider_active_connections`)**:
  Steady in healthy operation. Rising without bound while RPS is flat means
  upstream latency grew - that's the slow-upstream scenario's main signal.
- **Pending acquire (`reactor_netty_connection_provider_pending_acquire`)**:
  Non-zero only when the pool is saturated and new requests are queuing for a
  socket. Once this is > 0, the gateway is the bottleneck by definition.
- **Max connections (`reactor_netty_connection_provider_max_connections`)**:
  The static pool cap. Open-connections climbing toward this value is an early
  warning; hitting it is hard saturation.
- **5xx during the run**: Healthy gateways keep this at zero in steady state.
  Non-zero 5xx in the slow-upstream scenario after pool saturation is expected
  and useful - it's the gateway choosing to shed load rather than queue
  forever.
- **Latency percentiles**: p99 divergence from p50 is the earliest sign of
  queueing inside the data path.

## Architecture

```
  LoadDriver (HdrHistogram, open-loop scheduler, bounded Semaphore)
       |
       v
  EmbeddedGatewayStandIn   <-- a thin proxy we replace with the real
       |                       gateway-core/proxy module once it lands
       v
  FastUpstream | SlowUpstream | FlakyUpstream   (lean reactor-netty servers)

  GatewayMetricsSampler --> polls /actuator/prometheus every ~500 ms
```

The **stand-in gateway** is a deliberately tiny class
(`EmbeddedGatewayStandIn`) that proxies one URL and publishes the same
Prometheus metric names the real gateway does. When the sibling
`gateway-core/proxy` PR lands, swap the bean wiring in
`LoadSteps#startGateway(...)` to instantiate the real application and delete
the stand-in. The scenarios, driver, and upstreams stay exactly as-is.

## Adding a scenario

1. Implement `io.telekom.gateway.load_test.scenario.Scenario` under
   `src/main/java/.../scenario/`. Start the upstream state you need, call
   `driver.run(profile)`, and return a `ScenarioResult` with any custom
   observations keyed under clear names.
2. Add a `@When(...)` step in `LoadSteps` and the matching `@Then(...)`
   assertions.
3. Drop a `.feature` file under `src/test/resources/features/`. The
   `CucumberLoadIT` runner picks it up automatically.

## Non-functional contract for the harness itself

- No per-request logging anywhere on the hot path.
- No request- or response-body allocation churn - fixed `ByteBuf` per
  upstream, and the driver re-sends a single retained duplicate per send.
- Latency is in HdrHistogram from microsecond 100 to second 60, 3
  significant digits. Naive arrays of longs are never used.
- The slow-upstream scenario **fails** the Cucumber step if the Pearson
  correlation between elapsed time and open-connection count is not
  strongly positive - absence of signal is always a bug, either in the
  gateway or in the harness.
