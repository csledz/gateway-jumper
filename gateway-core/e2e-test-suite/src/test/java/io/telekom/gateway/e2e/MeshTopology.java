// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e;

import com.redis.testcontainers.RedisContainer;
import io.telekom.gateway.e2e.fixtures.EmbeddedZoneProxy;
import io.telekom.gateway.e2e.fixtures.ZoneHealthBus;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/**
 * TestContainers fixture that brings up the three-zone mesh used by every feature file.
 *
 * <p>Layout:
 *
 * <pre>
 *   Redis (shared zone-health bus)
 *   MockServer upstream-A     MockServer upstream-B     MockServer upstream-C
 *         ^                          ^                         ^
 *         |                          |                         |
 *      proxy-A  ---peer--->       proxy-B   ---peer--->     proxy-C
 *        (port)                     (port)                   (port)
 * </pre>
 *
 * Each proxy is either a {@link EmbeddedZoneProxy} Spring Boot context (default) or, when the
 * system property {@code gateway-core.image} is set to a published image reference, a {@link
 * GenericContainer} running that image. Feature files don't care which mode is active.
 */
@Slf4j
public class MeshTopology {

  public static final String ZONE_A = "A";
  public static final String ZONE_B = "B";
  public static final String ZONE_C = "C";
  public static final List<String> ZONES = List.of(ZONE_A, ZONE_B, ZONE_C);

  private static volatile MeshTopology INSTANCE;

  private final Network network = Network.newNetwork();
  @Getter private final RedisContainer redis;
  private final Map<String, MockServerContainer> upstreams = new ConcurrentHashMap<>();
  private final Map<String, Integer> proxyPorts = new ConcurrentHashMap<>();
  private final Map<String, ConfigurableApplicationContext> embeddedProxies =
      new ConcurrentHashMap<>();
  private final Map<String, GenericContainer<?>> containerProxies = new ConcurrentHashMap<>();
  @Getter private ReactiveStringRedisTemplate redisTemplate;
  @Getter private ZoneHealthBus healthBus;

  private MeshTopology() {
    this.redis =
        new RedisContainer(DockerImageName.parse("redis:7.2-alpine"))
            .withNetwork(network)
            .withNetworkAliases("redis")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));
  }

  public static synchronized MeshTopology getInstance() {
    if (INSTANCE == null) {
      MeshTopology t = new MeshTopology();
      t.start();
      Runtime.getRuntime().addShutdownHook(new Thread(t::stop, "mesh-topology-shutdown"));
      INSTANCE = t;
    }
    return INSTANCE;
  }

  private void start() {
    log.info("starting MeshTopology...");
    redis.start();
    for (String z : ZONES) {
      MockServerContainer ms =
          new MockServerContainer(
                  DockerImageName.parse("mockserver/mockserver:5.15.0")
                      .asCompatibleSubstituteFor("jamesdbloom/mockserver"))
              .withNetwork(network)
              .withNetworkAliases("upstream-" + z)
              .withStartupTimeout(Duration.ofMinutes(2));
      ms.start();
      upstreams.put(z, ms);
    }
    // Assign ports up front so peer maps can refer to each other.
    for (String z : ZONES) {
      proxyPorts.put(z, freePort());
    }
    // Boot all three proxies.
    Optional<String> image =
        Optional.ofNullable(System.getProperty("gateway-core.image"))
            .filter(s -> !s.isBlank() && !"null".equals(s));
    for (String z : ZONES) {
      if (image.isPresent()) {
        startContainerProxy(z, image.get());
      } else {
        startEmbeddedProxy(z);
      }
    }
    // Local tools (bus/template) used by steps.
    this.redisTemplate = buildLocalRedisTemplate();
    this.healthBus = new ZoneHealthBus(redisTemplate);
    this.healthBus.start();
    log.info(
        "MeshTopology up: redis=localhost:{}, proxies={}, upstreams={}",
        redis.getFirstMappedPort(),
        proxyPorts,
        upstreams.keySet());
  }

  private void stop() {
    log.info("stopping MeshTopology...");
    if (healthBus != null) healthBus.stop();
    embeddedProxies.values().forEach(ConfigurableApplicationContext::close);
    containerProxies.values().forEach(GenericContainer::stop);
    upstreams.values().forEach(MockServerContainer::stop);
    redis.stop();
    try {
      network.close();
    } catch (Exception ignored) {
      // best effort
    }
  }

  private void startEmbeddedProxy(String zone) {
    Map<String, String> peers = new LinkedHashMap<>();
    for (String z : ZONES) {
      if (!z.equals(zone)) {
        peers.put(z, "http://localhost:" + proxyPorts.get(z));
      }
    }
    MockServerContainer upstream = upstreams.get(zone);
    String upstreamUrl = "http://" + upstream.getHost() + ":" + upstream.getServerPort();
    ConfigurableApplicationContext ctx =
        EmbeddedZoneProxy.start(
            zone,
            proxyPorts.get(zone),
            redis.getHost(),
            redis.getFirstMappedPort(),
            peers,
            upstreamUrl);
    embeddedProxies.put(zone, ctx);
  }

  private void startContainerProxy(String zone, String image) {
    Map<String, String> peers = new LinkedHashMap<>();
    for (String z : ZONES) {
      if (!z.equals(zone)) {
        peers.put(z, "http://zone-" + z + ":8080");
      }
    }
    GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image))
            .withNetwork(network)
            .withNetworkAliases("zone-" + zone)
            .withExposedPorts(8080)
            .withEnv("GATEWAY_ZONE_NAME", zone)
            .withEnv("GATEWAY_ZONE_EDGE", "stargate-" + zone + ".local")
            .withEnv("SPRING_DATA_REDIS_HOST", "redis")
            .withEnv("SPRING_DATA_REDIS_PORT", "6379")
            .withEnv("GATEWAY_ZONE_UPSTREAM", "http://upstream-" + zone + ":1080")
            .withEnv("GATEWAY_ZONE_PEERS", encodePeers(peers))
            .withStartupTimeout(Duration.ofMinutes(3));
    c.start();
    containerProxies.put(zone, c);
    // Override the port with the externally mapped one so WebTestClient can reach it.
    proxyPorts.put(zone, c.getMappedPort(8080));
  }

  private ReactiveStringRedisTemplate buildLocalRedisTemplate() {
    RedisStandaloneConfiguration cfg =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getFirstMappedPort());
    cfg.setPassword(RedisPassword.none()); // RedisContainer doesn't set password
    LettuceConnectionFactory f = new LettuceConnectionFactory(cfg);
    f.afterPropertiesSet();
    return new ReactiveStringRedisTemplate(f);
  }

  public String proxyUrl(String zone) {
    return "http://localhost:" + proxyPorts.get(zone);
  }

  /**
   * Reset peer-token caches in every embedded proxy. No-op when running against container images -
   * tests that care about mint counts should restrict themselves to embedded mode, or rebuild the
   * image to expose a cache-reset endpoint.
   */
  public void resetPeerTokenCaches() {
    embeddedProxies
        .values()
        .forEach(
            ctx -> {
              try {
                ctx.getBean(io.telekom.gateway.e2e.fixtures.PeerTokenCache.class).reset();
              } catch (Exception ignored) {
                // bean missing or context closing - safe to skip
              }
            });
  }

  /**
   * Returns the peer-token mint count for the given origin zone, or {@code -1} when that zone is
   * running as a container image (counter is not exposed externally).
   */
  public int peerTokenMintCount(String originZone) {
    ConfigurableApplicationContext ctx = embeddedProxies.get(originZone);
    if (ctx == null) return -1;
    return ctx.getBean(io.telekom.gateway.e2e.fixtures.PeerTokenCache.class).mintCount();
  }

  public MockServerContainer upstream(String zone) {
    return upstreams.get(zone);
  }

  public int proxyPort(String zone) {
    return proxyPorts.get(zone);
  }

  private static String encodePeers(Map<String, String> peers) {
    StringBuilder sb = new StringBuilder();
    peers.forEach(
        (k, v) -> {
          if (sb.length() > 0) sb.append(',');
          sb.append(k).append('=').append(v);
        });
    return sb.toString();
  }

  private static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (Exception e) {
      throw new IllegalStateException("no free port", e);
    }
  }
}
