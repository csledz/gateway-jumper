// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.upstream;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entrypoint for running one of the dummy upstreams as a standalone process (or inside
 * a TestContainer) so scenarios can exercise the gateway across a real network hop instead of an
 * in-JVM loopback.
 *
 * <p>Selects which upstream to start via the {@code upstream.kind} system property or environment
 * variable ({@code fast|slow|flaky}; default {@code fast}) and reads its port from {@code
 * upstream.port} (default {@code 0} = ephemeral; logged on startup).
 */
@SpringBootApplication
@Slf4j
public class DummyUpstreamApp {

  public static void main(String[] args) {
    // Run under Spring Boot so the actuator-style endpoints and profiles are available if ever
    // needed; we do not bind a WebFlux/Webserver here because the upstream uses its own
    // reactor-netty instance on a dedicated port.
    SpringApplication app = new SpringApplication(DummyUpstreamApp.class);
    app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    app.run(args);

    String kind = resolve("upstream.kind", "fast").toLowerCase();
    int port = Integer.parseInt(resolve("upstream.port", "0"));

    switch (kind) {
      case "fast" -> {
        FastUpstream f = new FastUpstream();
        int bound = f.start(port);
        log.info("DummyUpstreamApp started FAST upstream on port {}", bound);
        park();
      }
      case "slow" -> {
        SlowUpstream s = new SlowUpstream();
        s.setLatency(
            SlowUpstream.LatencyDistribution.valueOf(
                resolve("upstream.dist", "CONSTANT").toUpperCase()),
            Duration.ofMillis(Long.parseLong(resolve("upstream.meanMs", "50"))));
        int bound = s.start(port);
        log.info(
            "DummyUpstreamApp started SLOW upstream on port {} (mean {} ms)",
            bound,
            s.currentMean().toMillis());
        park();
      }
      case "flaky" -> {
        FlakyUpstream fl = new FlakyUpstream();
        fl.setFailureRate(Double.parseDouble(resolve("upstream.failRate", "0.2")));
        int bound = fl.start(port);
        log.info(
            "DummyUpstreamApp started FLAKY upstream on port {} (fail rate {})",
            bound,
            fl.currentFailureRate());
        park();
      }
      default -> throw new IllegalArgumentException("unknown upstream.kind: " + kind);
    }
  }

  private static String resolve(String key, String def) {
    String v = System.getProperty(key);
    if (v != null) return v;
    v = System.getenv(key.replace('.', '_').toUpperCase());
    return v != null ? v : def;
  }

  private static void park() {
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
