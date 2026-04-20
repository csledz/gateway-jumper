// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class TieredTokenCacheTest {

  @Test
  void cacheHitSkipsFetcher() {
    TieredTokenCache cache = new TieredTokenCache();
    TieredTokenCache.Key key = new TieredTokenCache.Key("https://idp", "c1", List.of("read"));
    AtomicInteger calls = new AtomicInteger();

    Mono<String> fetched =
        cache.getOrFetch(
            key,
            () -> {
              calls.incrementAndGet();
              return Mono.just(new TieredTokenCache.FetchResult("tok-1", Duration.ofMinutes(5)));
            });
    assertThat(fetched.block()).isEqualTo("tok-1");
    assertThat(calls.get()).isEqualTo(1);

    // Second call: should hit cache
    Mono<String> again =
        cache.getOrFetch(
            key,
            () -> {
              calls.incrementAndGet();
              return Mono.just(new TieredTokenCache.FetchResult("tok-2", Duration.ofMinutes(5)));
            });
    assertThat(again.block()).isEqualTo("tok-1");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void singleFlightDeduplicatesConcurrentMisses() {
    TieredTokenCache cache = new TieredTokenCache();
    TieredTokenCache.Key key = new TieredTokenCache.Key("https://idp", "c1", List.of());
    AtomicInteger calls = new AtomicInteger();

    Mono<String> fetcherMono =
        Mono.defer(
                () ->
                    Mono.fromCallable(
                            () -> new TieredTokenCache.FetchResult("tok", Duration.ofMinutes(5)))
                        .delayElement(Duration.ofMillis(50)))
            .cache()
            .flatMap(r -> Mono.just(r).doOnNext(x -> calls.incrementAndGet()))
            .map(TieredTokenCache.FetchResult::token);

    // kick off 10 concurrent gets; single-flight should run the fetcher once
    List<String> results =
        Flux.range(0, 10)
            .flatMap(
                i ->
                    cache.getOrFetch(
                        key,
                        () -> {
                          calls.incrementAndGet();
                          return Mono.just(
                                  new TieredTokenCache.FetchResult("tok", Duration.ofMinutes(5)))
                              .delayElement(Duration.ofMillis(50));
                        }))
            .collectList()
            .block();

    assertThat(results).hasSize(10).allMatch(v -> v.equals("tok"));
    assertThat(calls.get()).isEqualTo(1);
  }
}
