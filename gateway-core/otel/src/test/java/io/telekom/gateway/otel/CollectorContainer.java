// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Lazy-started Testcontainers wrapper for {@code otel/opentelemetry-collector-contrib}.
 *
 * <p>The container is only spun up by the {@code @collector} scenario — the rest of the suite
 * asserts against in-memory exporters and therefore does not need Docker.
 */
public final class CollectorContainer extends GenericContainer<CollectorContainer> {

  private static final String IMAGE = "otel/opentelemetry-collector-contrib:0.150.1";

  /**
   * Minimal collector config: receive OTLP over gRPC + HTTP, pipe everything to a debug exporter so
   * scenarios can verify the collector accepts the data.
   */
  private static final String CONFIG_YAML =
      """
      receivers:
        otlp:
          protocols:
            grpc:
              endpoint: 0.0.0.0:4317
            http:
              endpoint: 0.0.0.0:4318
      processors:
        batch: {}
      exporters:
        debug:
          verbosity: detailed
      service:
        pipelines:
          traces:
            receivers: [otlp]
            processors: [batch]
            exporters: [debug]
          metrics:
            receivers: [otlp]
            processors: [batch]
            exporters: [debug]
          logs:
            receivers: [otlp]
            processors: [batch]
            exporters: [debug]
      """;

  public CollectorContainer() {
    super(DockerImageName.parse(IMAGE));
    withExposedPorts(4317, 4318)
        .withCopyToContainer(Transferable.of(CONFIG_YAML), "/etc/otelcol-contrib/config.yaml")
        .withCommand("--config=/etc/otelcol-contrib/config.yaml")
        .waitingFor(
            Wait.forLogMessage(".*Everything is ready.*", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
  }

  public String grpcEndpoint() {
    return "http://" + getHost() + ":" + getMappedPort(4317);
  }

  public String httpEndpoint() {
    return "http://" + getHost() + ":" + getMappedPort(4318);
  }
}
