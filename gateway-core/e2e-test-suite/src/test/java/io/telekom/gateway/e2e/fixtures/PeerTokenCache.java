// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.fixtures;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.Value;

/**
 * Token-caching stand-in for the peer-IdP client. Counts how many times a peer token was actually
 * minted (cache misses) so {@code token-caching.feature} can assert an upper bound.
 */
public final class PeerTokenCache {

  private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
  private final AtomicInteger mintCount = new AtomicInteger();

  public String getOrMint(String key, Supplier<String> minter, long ttlMs) {
    long now = System.currentTimeMillis();
    Entry cached = cache.get(key);
    if (cached != null && cached.expiresAt > now) {
      return cached.token;
    }
    synchronized (this) {
      cached = cache.get(key);
      if (cached != null && cached.expiresAt > now) {
        return cached.token;
      }
      String fresh = minter.get();
      mintCount.incrementAndGet();
      cache.put(key, new Entry(fresh, now + ttlMs));
      return fresh;
    }
  }

  public int mintCount() {
    return mintCount.get();
  }

  public void reset() {
    cache.clear();
    mintCount.set(0);
  }

  @Value
  private static class Entry {
    String token;
    long expiresAt;
  }
}
