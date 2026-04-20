// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.telekom.gateway.admin_status_api.service.InMemoryRuntimeStateReader;
import io.telekom.gateway.admin_status_api.service.RuntimeStateReader;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires default beans for the admin-status-api module.
 *
 * <p>Sibling modules that observe the real CRDs override {@link RuntimeStateReader} by contributing
 * their own bean of that type.
 */
@Configuration
public class AdminStatusApiConfig {

  @Bean
  @ConditionalOnMissingBean
  public Clock adminStatusApiClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(RuntimeStateReader.class)
  public RuntimeStateReader runtimeStateReader(Clock clock) {
    InMemoryRuntimeStateReader reader = new InMemoryRuntimeStateReader(clock);
    reader.seedDefaults();
    return reader;
  }

  @Bean
  public OpenAPI adminStatusOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("gateway-core admin status API")
                .version("v1")
                .description(
                    "Read-only status API over the live runtime state of the gateway. "
                        + "All mutations happen via kubectl against the CRDs.")
                .license(new License().name("Apache-2.0")));
  }
}
