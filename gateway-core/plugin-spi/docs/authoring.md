<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# Authoring a `gateway-core` plugin

This guide walks you through writing, packaging and dropping in a gateway plugin.
Plugins are **pure Java** — you never depend on Spring, WebFlux or any gateway-runtime
types. Your plugin is free to move between gateway-runtime versions as long as the
SPI version stays compatible.

## 1. Depend on the `-api` classifier jar

Add only the thin API jar to your plugin project:

```xml
<dependency>
  <groupId>io.telekom.gateway</groupId>
  <artifactId>plugin-spi</artifactId>
  <version>0.0.0</version>
  <classifier>api</classifier>
</dependency>
<dependency>
  <groupId>io.projectreactor</groupId>
  <artifactId>reactor-core</artifactId>
  <version>3.7.0</version>
  <scope>provided</scope>
</dependency>
```

The `api` classifier contains only `io.telekom.gateway.plugin_spi.api.*` — no Spring,
no loader, no filters. `reactor-core` comes from the gateway at runtime, so set it to
`provided`.

## 2. Implement `GatewayPlugin`

```java
package com.example;

import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import io.telekom.gateway.plugin_spi.api.PipelineStage;
import io.telekom.gateway.plugin_spi.api.PluginContext;
import reactor.core.publisher.Mono;

public class MyHeaderPlugin implements GatewayPlugin {
  @Override public String name()      { return "my-header"; }
  @Override public int order()        { return 1500; }            // user-plugin band
  @Override public PipelineStage stage() { return PipelineStage.PRE_UPSTREAM; }

  @Override
  public Mono<Void> apply(PluginContext ctx) {
    ctx.requestHeaders().add("X-My-Header", "hello");
    return Mono.empty();
  }
}
```

### Contract

| Method       | Purpose                                                                 |
|--------------|-------------------------------------------------------------------------|
| `name()`     | Unique identifier. Duplicates are dropped (first-seen wins, with WARN). |
| `order()`    | `int`, lower runs first **within a stage**. Default `1000`.             |
| `stage()`    | One of `PRE_ROUTING`, `PRE_UPSTREAM`, `POST_UPSTREAM`, `POST_RESPONSE`. |
| `apply(ctx)` | Returns a non-null `Mono<Void>`. Errors abort the pipeline.             |

### Reserved order bands

| Range          | Owner           |
|----------------|-----------------|
| `0..99`        | Platform internals — do not use. |
| `100..999`     | Infra plugins (tracing, auth, rate-limit). |
| `1000..`       | User plugins.   |

## 3. Register via `ServiceLoader`

Create `src/main/resources/META-INF/services/io.telekom.gateway.plugin_spi.api.GatewayPlugin`
and list one FQN per line:

```
com.example.MyHeaderPlugin
```

## 4. Package and drop

`mvn package` produces `my-plugin-1.0.0.jar`. Copy it into the directory pointed at
by `gateway.plugin-spi.directory` (env var `GATEWAY_PLUGIN_DIR`). The watcher reloads
within ~1s — no restart required.

```bash
cp target/my-plugin-1.0.0.jar /etc/gateway/plugins/
```

## 5. `PluginContext` tour

| Accessor                          | What it does                                                         |
|-----------------------------------|----------------------------------------------------------------------|
| `ctx.requestHeaders().set(...)`   | Modify downstream request headers (case-insensitive).               |
| `ctx.responseHeaders().set(...)`  | Modify upstream response headers (empty before `POST_UPSTREAM`).    |
| `ctx.putAttribute("k", v)`        | Share state with later plugins in the same request.                 |
| `ctx.attribute("k", Foo.class)`   | Safe read of an attribute; returns `Optional.empty()` on mismatch.  |
| `ctx.exchange()`                  | Opaque handle. Avoid — reach for the accessors above.               |

## 6. Testing locally

```java
@Test
void sets_my_header() {
  var plugin = new MyHeaderPlugin();
  var headers = new InMemoryHeaders();          // from your own test kit
  var ctx = new PluginContext(null, PipelineStage.PRE_UPSTREAM,
                              new HashMap<>(), headers, new InMemoryHeaders());
  StepVerifier.create(plugin.apply(ctx)).verifyComplete();
  assertThat(headers.getAll("X-My-Header")).containsExactly("hello");
}
```

## 7. Gotchas

- **No Spring types in public APIs** — the runtime will *not* inject beans into your
  plugin. Keep configuration in a static factory or read from `System.getenv`.
- **Thread-safety** — one instance handles every request. No mutable instance state.
- **No blocking** — the runtime is reactive. Wrap blocking work with
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- **Classloader isolation** — each reload creates a fresh child classloader. Do not
  hold static references to classes from other plugin jars.
