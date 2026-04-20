// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/** Smoke test that the Spring context boots for the skeleton module. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class ApplicationSmokeTest {

  @Test
  void contextLoads() {}
}
