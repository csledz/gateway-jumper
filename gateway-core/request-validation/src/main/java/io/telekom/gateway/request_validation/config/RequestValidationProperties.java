// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code gateway.validation.*}. Supplies module-wide defaults. */
@ConfigurationProperties(prefix = "gateway.validation")
public record RequestValidationProperties(
    long maxRequestBytes, List<String> defaultAllowedContentTypes) {

  public RequestValidationProperties {
    if (maxRequestBytes <= 0) {
      maxRequestBytes = 1_048_576L;
    }
    if (defaultAllowedContentTypes == null || defaultAllowedContentTypes.isEmpty()) {
      defaultAllowedContentTypes =
          List.of(
              "application/json", "application/problem+json", "application/x-www-form-urlencoded");
    }
  }
}
