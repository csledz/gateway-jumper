<!--
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# gateway-core :: plugin-spi

`ServiceLoader`-based plugin SPI for the Kong-free `gateway-core`. Drop a JAR into
the watch directory to add behaviour at one of four pipeline stages — no recompile,
no restart.

## Why

Jumper's filters are hard-wired Spring beans. Operators asked: *"how do I add a
header rewrite without forking you?"* `plugin-spi` is the answer. Plugin authors
depend only on a thin `-api` jar; they never see Spring or WebFlux.

## Contract at a glance

```java
public interface GatewayPlugin {
  String name();                         // unique
  default int order() { return 1000; }   // lower runs first within stage
  PipelineStage stage();                 // PRE_ROUTING | PRE_UPSTREAM | POST_UPSTREAM | POST_RESPONSE
  Mono<Void> apply(PluginContext ctx);
}
```

Register via `META-INF/services/io.telekom.gateway.plugin_spi.api.GatewayPlugin`.

See [`docs/authoring.md`](docs/authoring.md) for the full guide.

## Runtime wiring

The module auto-configures three beans:

| Bean                   | Role                                                               |
|------------------------|--------------------------------------------------------------------|
| `PluginRegistry`       | Hot-swappable list of plugins, queryable by stage.                 |
| `PluginLoader`         | Scans classpath + drop directory; optional directory watcher.      |
| `PluginDispatchFilter` | WebFlux `WebFilter` that runs plugins around the rest of the chain. |

Configuration keys (see `application.yml`):

```yaml
gateway:
  plugin-spi:
    enabled: true                # master switch
    directory: /etc/gateway/plugins
    watch: true                  # hot-reload on directory changes
    enabled-plugins:             # allow-list; empty = everything discovered
      - x-request-id
```

## Publishing for plugin authors

The build emits two JARs:

| Coordinate                                         | Contents                              |
|----------------------------------------------------|---------------------------------------|
| `io.telekom.gateway:plugin-spi:0.0.0`              | Runtime (loader, registry, filter).   |
| `io.telekom.gateway:plugin-spi:0.0.0:api`          | Only `api.*` — what authors depend on. |

## Bundled example: `x-request-id`

Adds / forwards an `X-Request-Id` header at `PRE_ROUTING`, mirroring it onto the
response. Registered in `META-INF/services` — serves as the reference shape for
external plugins.

## Build & test

```bash
# from gateway-core/plugin-spi/
../../mvnw -pl . verify -DskipITs=false
```

## End-to-end recipe

From `gateway-core/plugin-spi/`:

```bash
docker-compose -f ../../docker-compose.yml up -d redis jaeger prometheus echo
until docker-compose -f ../../docker-compose.yml exec -T redis redis-cli -a foobar ping | grep -q PONG; do sleep 1; done
../../mvnw -pl . verify -DskipITs=false
docker-compose -f ../../docker-compose.yml down
```

## Integration with the sibling `proxy` module

`PipelineStage` is declared canonically **here**. The sibling `proxy` module ships
an identical enum for release-independence; both must track in lock-step. A future
release will extract `PipelineStage` into a shared `-core` jar once module maturity
justifies the coupling.
