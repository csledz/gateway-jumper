// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.fixtures;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import java.net.URI;
import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Embedded minimal proxy used by the e2e suite when sibling gateway-core modules aren't built yet.
 *
 * <p>Implements inline just the pieces the features need:
 *
 * <ul>
 *   <li>mesh-JWT minting on hop out ({@link MeshKeyStore}) + verification on hop in
 *   <li>zone-health pub/sub ({@link ZoneHealthBus}) + failover to the first healthy peer
 *   <li>token caching with a mint-count meter ({@link PeerTokenCache})
 * </ul>
 *
 * <p>Each zone runs one instance, addressed by port. Peer zones are registered at boot time via
 * {@link ZoneTopologyProperties}. When the real modules land, this whole class goes away and the
 * features re-target the real gateway-core containers.
 */
@SpringBootApplication
@Slf4j
public class EmbeddedZoneProxy {

  public static ConfigurableApplicationContext start(
      String zone,
      int port,
      String redisHost,
      int redisPort,
      Map<String, String> peers,
      String upstreamUrl) {
    return new SpringApplication(EmbeddedZoneProxy.class)
        .run(
            "--spring.main.web-application-type=reactive",
            "--spring.application.name=zone-" + zone,
            "--server.port=" + port,
            "--gateway.zone.name=" + zone,
            "--gateway.zone.edge=stargate-" + zone + ".local",
            "--spring.data.redis.host=" + redisHost,
            "--spring.data.redis.port=" + redisPort,
            "--gateway.zone.peers=" + encodePeers(peers),
            "--gateway.zone.upstream=" + upstreamUrl);
  }

  private static String encodePeers(Map<String, String> peers) {
    List<String> pairs = new ArrayList<>();
    peers.forEach((k, v) -> pairs.add(k + "=" + v));
    return String.join(",", pairs);
  }

  @Bean
  ZoneTopologyProperties zoneProps() {
    return new ZoneTopologyProperties();
  }

  @Bean
  PeerTokenCache peerTokenCache() {
    return new PeerTokenCache();
  }

  @Bean(destroyMethod = "stop")
  ZoneHealthBus zoneHealthBus(ReactiveRedisConnectionFactory f) {
    // Build our own String template from the autoconfigured factory to avoid clashing with
    // Boot's generic reactiveRedisTemplate bean graph.
    ZoneHealthBus bus = new ZoneHealthBus(new ReactiveStringRedisTemplate(f));
    bus.start();
    return bus;
  }

  @Bean
  WebClient peerWebClient() {
    return WebClient.builder().build();
  }

  @Bean
  RouterFunction<ServerResponse> routes(
      ZoneTopologyProperties props,
      ZoneHealthBus health,
      PeerTokenCache tokenCache,
      WebClient peer) {
    return RouterFunctions.route()
        .route(req -> true, req -> new MeshHandler(props, health, tokenCache, peer).handle(req))
        .build();
  }

  /** Inbound hop receiver + outbound hop dispatcher. */
  static class MeshHandler {
    /**
     * Resolves the verification key by trusting the {@code originZone} body claim. The real
     * mesh-jwt module will swap this for a JWKS-backed resolver keyed by JWS {@code kid}.
     */
    private static final SigningKeyResolverAdapter KEY_RESOLVER =
        new SigningKeyResolverAdapter() {
          @Override
          public Key resolveSigningKey(JwsHeader header, Claims claims) {
            Object originZone = claims.get("originZone");
            if (originZone == null) throw new IllegalArgumentException("missing originZone claim");
            return MeshKeyStore.keyFor(String.valueOf(originZone)).getPublic();
          }
        };

    private final ZoneTopologyProperties props;
    private final ZoneHealthBus health;
    private final PeerTokenCache tokenCache;
    private final WebClient peer;

    MeshHandler(ZoneTopologyProperties p, ZoneHealthBus h, PeerTokenCache t, WebClient w) {
      this.props = p;
      this.health = h;
      this.tokenCache = t;
      this.peer = w;
    }

    Mono<ServerResponse> handle(ServerRequest req) {
      String path = req.path();
      if ("/actuator/health".equals(path)) {
        return ServerResponse.ok().bodyValue("{\"status\":\"UP\"}");
      }
      if (path.startsWith("/mesh/inbound")) {
        return handleInbound(req);
      }
      return handleOutbound(req);
    }

    Mono<ServerResponse> handleInbound(ServerRequest req) {
      String auth = req.headers().firstHeader(HttpHeaders.AUTHORIZATION);
      if (auth == null || !auth.startsWith("Bearer ")) {
        return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
      }
      String token = auth.substring("Bearer ".length());
      Claims claims;
      try {
        claims =
            Jwts.parserBuilder()
                .setSigningKeyResolver(KEY_RESOLVER)
                .setAllowedClockSkewSeconds(60)
                .build()
                .parseClaimsJws(token)
                .getBody();
      } catch (Exception e) {
        log.warn("mesh-jwt verification failed: {}", e.getMessage());
        return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
      }
      String upstream = props.upstreamFor(req.path());
      if (upstream == null) {
        return ServerResponse.status(HttpStatus.NOT_FOUND).build();
      }
      String originZone = String.valueOf(claims.get("originZone"));
      String originStargate = String.valueOf(claims.get("originStargate"));
      String audience = String.valueOf(claims.getAudience());
      return peer.get()
          .uri(URI.create(upstream + req.path()))
          .header("X-Forwarded-By", "zone-" + props.name)
          .header("X-Origin-Zone", originZone)
          .header("X-Origin-Stargate", originStargate)
          .header("X-Mesh-Audience", audience)
          .exchangeToMono(
              r ->
                  r.bodyToMono(String.class)
                      .defaultIfEmpty("")
                      .flatMap(
                          body ->
                              ServerResponse.status(r.statusCode())
                                  // Echo the mesh-JWT claims back to the client so the outbound
                                  // proxy (and ultimately the test) can assert on them without
                                  // needing MockServer to forward them.
                                  .header("X-Forwarded-By", "zone-" + props.name)
                                  .header("X-Origin-Zone", originZone)
                                  .header("X-Origin-Stargate", originStargate)
                                  .header("X-Mesh-Audience", audience)
                                  .headers(h -> h.addAll(r.headers().asHttpHeaders()))
                                  .bodyValue(body)));
    }

    Mono<ServerResponse> handleOutbound(ServerRequest req) {
      String target = req.headers().firstHeader("X-Target-Zone");
      if (target == null) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
            .bodyValue("missing X-Target-Zone header");
      }
      String skipHeader = req.headers().firstHeader("X-Failover-Skip-Zone");
      List<String> skip = skipHeader == null ? List.of() : List.of(skipHeader.split(","));
      List<String> attempts = new ArrayList<>();
      // Primary target, then failover candidates.
      String chosen = pickHealthy(target, skip, attempts);
      if (chosen == null) {
        return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Attempted-Zones", String.join(",", attempts))
            .bodyValue("no healthy peer");
      }
      String token =
          tokenCache.getOrMint(
              "peer-" + chosen,
              () ->
                  MeshKeyStore.issue(props.name, "zone-" + chosen, props.edge, /* ttlMs */ 30_000),
              /* cacheTtlMs */ 25_000);
      String peerUrl = props.peerUrl(chosen);
      String finalSkip =
          skipHeader == null
              ? (chosen.equals(target) ? null : target)
              : (chosen.equals(target) ? skipHeader : skipHeader + "," + target);
      return peer.method(HttpMethod.GET)
          .uri(URI.create(peerUrl + "/mesh/inbound" + req.path()))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .header("X-Failover-Chosen-Zone", chosen)
          .headers(
              h -> {
                if (finalSkip != null) h.set("X-Failover-Skip-Zone", finalSkip);
              })
          .exchangeToMono(
              r ->
                  r.bodyToMono(String.class)
                      .defaultIfEmpty("")
                      .flatMap(
                          body -> {
                            ServerResponse.BodyBuilder b =
                                ServerResponse.status(r.statusCode())
                                    .header("X-Failover-Chosen-Zone", chosen)
                                    .header("X-Attempted-Zones", String.join(",", attempts));
                            if (finalSkip != null) b.header("X-Failover-Skip-Zone", finalSkip);
                            copyResponseHeaders(r.headers().asHttpHeaders(), b);
                            return b.bodyValue(body);
                          }));
    }

    private static void copyResponseHeaders(HttpHeaders src, ServerResponse.BodyBuilder dst) {
      src.forEach(
          (k, vs) -> {
            if (k.toLowerCase().startsWith("x-")) {
              vs.forEach(v -> dst.header(k, v));
            }
          });
    }

    private String pickHealthy(String primary, List<String> skip, List<String> attempts) {
      List<String> order = new ArrayList<>();
      order.add(primary);
      props.failoverOrder(primary).forEach(order::add);
      for (String z : order) {
        attempts.add(z);
        if (skip.contains(z)) continue;
        if (health.getUnhealthy().contains(z)) continue;
        return z;
      }
      return null;
    }
  }

  /** Topology wiring: this zone's own name + edge + peer map + upstream map. */
  static class ZoneTopologyProperties {
    @org.springframework.beans.factory.annotation.Value("${gateway.zone.name}")
    String name;

    @org.springframework.beans.factory.annotation.Value("${gateway.zone.edge}")
    String edge;

    @org.springframework.beans.factory.annotation.Value("${gateway.zone.peers:}")
    String peersRaw;

    @org.springframework.beans.factory.annotation.Value("${gateway.zone.upstream:}")
    String upstreamRaw;

    private final Map<String, String> peerCache = new ConcurrentHashMap<>();

    String peerUrl(String zone) {
      return peers().get(zone);
    }

    Map<String, String> peers() {
      if (!peerCache.isEmpty()) return peerCache;
      if (peersRaw == null || peersRaw.isBlank()) return peerCache;
      for (String p : peersRaw.split(",")) {
        String[] kv = p.split("=", 2);
        if (kv.length == 2) peerCache.put(kv[0].trim(), kv[1].trim());
      }
      return peerCache;
    }

    List<String> failoverOrder(String primary) {
      List<String> out = new ArrayList<>();
      peers()
          .keySet()
          .forEach(
              z -> {
                if (!z.equals(primary)) out.add(z);
              });
      return out;
    }

    String upstreamFor(String path) {
      // For the minimal proxy, the upstream is the same for all paths in the inbound direction.
      return (upstreamRaw == null || upstreamRaw.isBlank()) ? null : upstreamRaw;
    }
  }
}
