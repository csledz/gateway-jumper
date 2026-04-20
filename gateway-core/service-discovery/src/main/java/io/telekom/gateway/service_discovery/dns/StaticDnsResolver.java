// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.dns;

import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import io.telekom.gateway.service_discovery.api.ServiceRef;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Resolver that delegates to the JVM DNS resolver. Results are cached per (host, port, scheme) for
 * a configurable TTL to avoid hammering the resolver on every request.
 *
 * <p>The actual {@code InetAddress.getAllByName} call is moved to {@link
 * Schedulers#boundedElastic()} because it is a blocking system call that must never run on a Netty
 * event loop.
 */
@Slf4j
public class StaticDnsResolver implements ServiceResolver {

  public static final String SCHEME = "dns";

  private final Duration ttl;
  private final DnsLookup lookup;
  private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

  public StaticDnsResolver(Duration ttl) {
    this(ttl, InetAddress::getAllByName);
  }

  /** Test-seam constructor — lets features inject a deterministic lookup. */
  public StaticDnsResolver(Duration ttl, DnsLookup lookup) {
    this.ttl = Objects.requireNonNull(ttl);
    this.lookup = Objects.requireNonNull(lookup);
  }

  @Override
  public String scheme() {
    return SCHEME;
  }

  @Override
  public Mono<List<ServiceEndpoint>> resolve(ServiceRef ref) {
    String host = ref.name();
    int port = ref.port() == 0 ? 80 : ref.port();
    String key = host + ":" + port;
    Entry cached = cache.get(key);
    if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
      return Mono.just(cached.endpoints);
    }
    return Mono.fromCallable(() -> doLookup(host, port))
        .subscribeOn(Schedulers.boundedElastic())
        .doOnNext(list -> cache.put(key, new Entry(list, Instant.now().plus(ttl))))
        .doOnError(e -> log.warn("DNS lookup failed for {}: {}", host, e.toString()))
        .onErrorReturn(List.of());
  }

  private List<ServiceEndpoint> doLookup(String host, int port) throws UnknownHostException {
    InetAddress[] addrs = lookup.getAllByName(host);
    return Arrays.stream(addrs)
        .map(a -> ServiceEndpoint.of(a.getHostAddress(), port, "http"))
        .toList();
  }

  /** Clear cache — used by tests to bypass TTL. */
  public void invalidateCache() {
    cache.clear();
  }

  /** Injectable DNS lookup — default delegates to {@link InetAddress#getAllByName(String)}. */
  @FunctionalInterface
  public interface DnsLookup {
    InetAddress[] getAllByName(String host) throws UnknownHostException;
  }

  private record Entry(List<ServiceEndpoint> endpoints, Instant expiresAt) {}
}
