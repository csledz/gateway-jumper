// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * In-memory cache for outbound bearer tokens. Single-flight de-duplication: if two requests miss
 * the same key concurrently, only one upstream fetch runs.
 *
 * <p>Keys are {@code (endpoint, clientId, scope-set)}. The cache subtracts a 10-second buffer from
 * the advertised expiry so a token is never served in the last 10s of its life.
 */
@Slf4j
public class TieredTokenCache {

  private static final Duration EXPIRY_BUFFER = Duration.ofSeconds(10);

  private final Cache<Key, CachedToken> cache;
  private final ConcurrentHashMap<Key, Mono<String>> inflight = new ConcurrentHashMap<>();

  public TieredTokenCache() {
    this.cache = Caffeine.newBuilder().maximumSize(10_000).build();
  }

  /**
   * Return the cached token for {@code key} if fresh; otherwise invoke {@code fetcher}. The fetcher
   * returns a (token, ttl) pair; the cache stores it with the buffer applied.
   */
  public Mono<String> getOrFetch(Key key, Supplier<Mono<FetchResult>> fetcher) {
    CachedToken hit = cache.getIfPresent(key);
    if (hit != null && hit.freshUntil.isAfter(Instant.now())) {
      return Mono.just(hit.token);
    }
    return inflight.computeIfAbsent(
        key,
        k ->
            fetcher
                .get()
                .map(
                    result -> {
                      Instant freshUntil = Instant.now().plus(result.ttl).minus(EXPIRY_BUFFER);
                      cache.put(k, new CachedToken(result.token, freshUntil));
                      return result.token;
                    })
                .doFinally(sig -> inflight.remove(k))
                .cache());
  }

  /** Lookup key. */
  public record Key(String endpoint, String clientId, List<String> scopes) {
    public Key {
      scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
  }

  /** Result of a token fetch. */
  public record FetchResult(String token, Duration ttl) {
    public FetchResult {
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(ttl, "ttl");
    }
  }

  private record CachedToken(String token, Instant freshUntil) {}
}
