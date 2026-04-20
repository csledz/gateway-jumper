// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.testkit.hydra;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for {@link HydraClient}.
 *
 * <p>The test is skipped unless the {@code HYDRA_CLIENTS_FILE} environment variable points at an
 * existing, readable seed file. In CI this means the docker-compose landscape must already be up
 * and {@code hydra-seed} must have written the secrets file. Locally, {@code ./mvnw verify} in a
 * fresh checkout will skip this test, which is the intended behaviour.
 */
class HydraClientTest {

  @Test
  void fetches_client_credentials_token_when_landscape_is_up() {
    String path = System.getenv(HydraClient.ENV_HYDRA_CLIENTS_FILE);
    assumeTrue(
        path != null && !path.isBlank(),
        "HYDRA_CLIENTS_FILE not set — skipping (requires docker-compose landscape)");
    Path clientsFile = Path.of(path);
    assumeTrue(
        Files.isReadable(clientsFile),
        "HYDRA_CLIENTS_FILE='" + path + "' is not readable — skipping");

    HydraClient client = new HydraClient(clientsFile);

    String token =
        client
            .clientCredentialsToken("gateway-test-cc", List.of("read"))
            .block(Duration.ofSeconds(10));

    // The raw token value is intentionally never asserted against a literal; we just
    // check that something non-empty and JWT-shaped came back.
    assertNotNull(token, "token must not be null");
    assertTrue(token.length() > 20, "token must have non-trivial length");
    assertTrue(
        token.chars().filter(ch -> ch == '.').count() >= 2,
        "access_token should be a JWT (3 base64url segments)");
  }
}
