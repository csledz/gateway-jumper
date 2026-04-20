// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.push;

import io.telekom.gateway.controller.snapshot.ConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Thin HTTP client around {@link WebClient} with spring-retry semantics. Sitting in its own bean
 * guarantees {@link Retryable} is honoured via the AOP proxy when {@link DataPlanePushService#push}
 * invokes it.
 */
@Slf4j
@Component
public class DataPlaneHttpClient {

  private final WebClient webClient;

  public DataPlaneHttpClient(WebClient.Builder webClientBuilder) {
    this.webClient = webClientBuilder.build();
  }

  /** POSTs the snapshot to {@code url + /config}; retries on 5xx / IO errors, not on 4xx. */
  @Retryable(
      retryFor = {Exception.class},
      noRetryFor = {
        WebClientResponseException.BadRequest.class,
        WebClientResponseException.NotFound.class,
        WebClientResponseException.Unauthorized.class,
        WebClientResponseException.Forbidden.class
      },
      maxAttempts = 3,
      backoff = @Backoff(delay = 200, multiplier = 2.0))
  public void pushTo(String url, ConfigSnapshot snap) {
    log.debug("Pushing snapshot {} to {}", snap.getSnapshotId(), url);
    webClient
        .post()
        .uri(url + "/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(snap)
        .retrieve()
        .toBodilessEntity()
        .block();
  }
}
