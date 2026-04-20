// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.rate_limiter.config;

import io.telekom.gateway.rate_limiter.api.RateLimitPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code gateway.rate-limit.*}. */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
    int defaultLimit, int defaultWindowSeconds, int defaultBurst, boolean failOpen) {

  public RateLimitProperties {
    if (defaultLimit <= 0) {
      defaultLimit = 100;
    }
    if (defaultWindowSeconds <= 0) {
      defaultWindowSeconds = 60;
    }
    if (defaultBurst < 0) {
      defaultBurst = 0;
    }
  }

  public RateLimitPolicy defaultPolicy() {
    return new RateLimitPolicy(defaultLimit, defaultWindowSeconds, defaultBurst, null);
  }
}
