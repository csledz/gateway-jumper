// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.request_validation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves JSON Schema references ({@code schemaRef}) to compiled {@link JsonSchema} validators,
 * caching compiled instances indefinitely (bounded by max size).
 *
 * <p>Supported schemes on {@code schemaRef}:
 *
 * <ul>
 *   <li>{@code classpath:/path/to/schema.json} — loaded from the module classpath,
 *   <li>{@code file:/absolute/path.json} — read from disk,
 *   <li>plain path without scheme — treated as classpath.
 * </ul>
 */
@Slf4j
public class JsonSchemaRegistry {

  private final ObjectMapper mapper;
  private final JsonSchemaFactory factory;
  private final Cache<String, JsonSchema> cache;

  public JsonSchemaRegistry(ObjectMapper mapper) {
    this.mapper = mapper;
    this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    this.cache = Caffeine.newBuilder().maximumSize(1024).build();
  }

  public Optional<JsonSchema> resolve(String schemaRef) {
    if (schemaRef == null || schemaRef.isBlank()) {
      return Optional.empty();
    }
    JsonSchema cached = cache.getIfPresent(schemaRef);
    if (cached != null) {
      return Optional.of(cached);
    }
    try {
      JsonNode doc = readDocument(schemaRef);
      JsonSchema compiled = factory.getSchema(doc);
      cache.put(schemaRef, compiled);
      return Optional.of(compiled);
    } catch (IOException e) {
      log.warn("failed to load JSON schema {}: {}", schemaRef, e.getMessage());
      return Optional.empty();
    }
  }

  private JsonNode readDocument(String schemaRef) throws IOException {
    if (schemaRef.startsWith("file:")) {
      URI uri = URI.create(schemaRef);
      return mapper.readTree(Files.newInputStream(Path.of(uri)));
    }
    String resourcePath =
        schemaRef.startsWith("classpath:") ? schemaRef.substring("classpath:".length()) : schemaRef;
    if (resourcePath.startsWith("/")) {
      resourcePath = resourcePath.substring(1);
    }
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("classpath resource not found: " + resourcePath);
      }
      return mapper.readTree(in);
    }
  }
}
