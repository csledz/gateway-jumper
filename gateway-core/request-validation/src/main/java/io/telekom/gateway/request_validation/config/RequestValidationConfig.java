// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.telekom.gateway.request_validation.api.PolicyLookup;
import io.telekom.gateway.request_validation.contenttype.ContentTypeFilter;
import io.telekom.gateway.request_validation.cors.CorsFilter;
import io.telekom.gateway.request_validation.schema.JsonSchemaFilter;
import io.telekom.gateway.request_validation.schema.JsonSchemaRegistry;
import io.telekom.gateway.request_validation.size.SizeLimitFilter;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the four request-validation filters. */
@Configuration
@EnableConfigurationProperties(RequestValidationProperties.class)
public class RequestValidationConfig {

  @Bean
  @ConditionalOnMissingBean
  public PolicyLookup defaultPolicyLookup() {
    return exchange -> Optional.empty();
  }

  @Bean
  public JsonSchemaRegistry jsonSchemaRegistry(ObjectMapper mapper) {
    return new JsonSchemaRegistry(mapper);
  }

  @Bean
  public CorsFilter corsFilter(PolicyLookup lookup) {
    return new CorsFilter(lookup);
  }

  @Bean
  public ContentTypeFilter contentTypeFilter(
      PolicyLookup lookup, RequestValidationProperties props) {
    return new ContentTypeFilter(lookup, props);
  }

  @Bean
  public SizeLimitFilter sizeLimitFilter(PolicyLookup lookup, RequestValidationProperties props) {
    return new SizeLimitFilter(lookup, props);
  }

  @Bean
  public JsonSchemaFilter jsonSchemaFilter(
      PolicyLookup lookup, JsonSchemaRegistry registry, ObjectMapper mapper) {
    return new JsonSchemaFilter(lookup, registry, mapper);
  }
}
