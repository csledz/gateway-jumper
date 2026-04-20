// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.admin_status_api.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security for the read-only admin surface.
 *
 * <p>{@code /admin/**} is protected. In dev (default), HTTP basic auth is enabled with a
 * configurable user. In prod, the module is configured to enforce mTLS (x509) and basic auth is
 * disabled. The Swagger UI at {@code /admin/docs} and the OpenAPI spec are also gated.
 *
 * <p>Toggle via {@code admin.security.mode} (values: {@code basic}, {@code mtls}).
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
public class AdminSecurityConfig {

  @Bean
  public PasswordEncoder adminPasswordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  public ReactiveUserDetailsService adminUserDetailsService(
      PasswordEncoder passwordEncoder,
      @Value("${admin.security.user:admin}") String user,
      @Value("${admin.security.password:admin}") String password) {
    UserDetails admin =
        User.withUsername(user).password(passwordEncoder.encode(password)).roles("ADMIN").build();
    return new MapReactiveUserDetailsService(admin);
  }

  @Bean
  public SecurityWebFilterChain adminSecurityWebFilterChain(
      ServerHttpSecurity http, @Value("${admin.security.mode:basic}") String mode) {
    log.info("Configuring admin-status-api security in mode={}", mode);
    http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            a ->
                a.pathMatchers("/actuator/health", "/actuator/info")
                    .permitAll()
                    .pathMatchers("/admin/**", "/v3/api-docs/**", "/webjars/**")
                    .authenticated()
                    .anyExchange()
                    .permitAll());

    if ("mtls".equalsIgnoreCase(mode)) {
      http.x509(x -> x.principalExtractor(cert -> cert.getSubjectX500Principal().getName()));
    } else {
      http.httpBasic(org.springframework.security.config.Customizer.withDefaults());
    }
    return http.build();
  }
}
