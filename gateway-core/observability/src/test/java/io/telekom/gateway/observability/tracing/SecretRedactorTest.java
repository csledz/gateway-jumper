// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

  private final SecretRedactor redactor = new SecretRedactor();

  @Test
  void stripsOAuthAccessTokenFromQueryString() {
    String url = redactor.filterQueryParams("https://api/resource?access_token=xyz&ok=1");
    assertThat(url).doesNotContain("xyz").contains("ok=1");
  }

  @Test
  void stripsOAuthAuthCodeAndState() {
    String url = redactor.filterQueryParams("https://api/cb?code=abc&state=def&other=keep");
    assertThat(url).doesNotContain("abc").doesNotContain("def").contains("other=keep");
  }

  @Test
  void stripsBareTokenAndJwt() {
    String url = redactor.filterQueryParams("https://api?token=t1&jwt=j1&ok=y");
    assertThat(url).doesNotContain("t1").doesNotContain("j1").contains("ok=y");
  }

  @Test
  void stripsApiKeyVariants() {
    String url = redactor.filterQueryParams("https://api?api_key=k1&api-key=k2&apikey=k3");
    assertThat(url).doesNotContain("k1").doesNotContain("k2").doesNotContain("k3");
  }

  @Test
  void redactsAuthorizationHeader() {
    assertThat(redactor.redactHeaderValue("Authorization", "Bearer abc")).isEqualTo("[redacted]");
  }

  @Test
  void redactsBasicAuthHeader() {
    assertThat(redactor.redactHeaderValue("authorization", "Basic Zm9vOmJhcg=="))
        .isEqualTo("[redacted]");
  }

  @Test
  void redactsApiKeyHeader() {
    assertThat(redactor.redactHeaderValue("X-API-Key", "opaque")).isEqualTo("[redacted]");
  }

  @Test
  void redactsConsumerTokenHeaderUsedByMesh() {
    assertThat(redactor.redactHeaderValue("X-Consumer-Token", "inner-jwt")).isEqualTo("[redacted]");
  }

  @Test
  void redactsCookieHeader() {
    assertThat(redactor.redactHeaderValue("Cookie", "session=abc; csrf=xyz"))
        .isEqualTo("[redacted]");
  }

  @Test
  void leavesSafeHeadersAlone() {
    assertThat(redactor.redactHeaderValue("User-Agent", "curl/8")).isEqualTo("curl/8");
    assertThat(redactor.redactHeaderValue("X-Request-Id", "abc-123")).isEqualTo("abc-123");
  }
}
