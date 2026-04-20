// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic Logback configuration that replaces the root console appender with a
 * Logstash-JSON-encoded one. Toggled via {@code gateway.observability.logging.json-enabled}
 * (default {@code true}) so tests can opt-out.
 *
 * <p>The JSON encoder includes MDC by default. Keys populated by {@link ReactorMdcPropagation}
 * (traceId, spanId, route, zone) therefore appear as top-level fields in every log line.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "gateway.observability.logging",
    name = "json-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StructuredLogbackConfig {

  @Value("${gateway.observability.logging.appender-name:CONSOLE}")
  private String appenderName;

  @Value("${gateway.observability.logging.include-mdc-keys:traceId,spanId,route,zone}")
  private List<String> includeMdcKeys;

  @PostConstruct
  void configure() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.setIncludeMdc(true);
    encoder.setIncludeContext(false);
    if (includeMdcKeys != null && !includeMdcKeys.isEmpty()) {
      encoder.setIncludeMdcKeyNames(new java.util.ArrayList<>(includeMdcKeys));
    }
    encoder.start();

    ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
    appender.setContext(context);
    appender.setName(appenderName);
    appender.setEncoder(encoder);
    appender.start();

    // Replace the default console appender so we emit a single JSON stream.
    root.detachAppender("CONSOLE");
    if (!"CONSOLE".equals(appenderName)) {
      root.detachAppender(appenderName);
    }
    root.addAppender(appender);
    if (root.getLevel() == null) {
      root.setLevel(Level.INFO);
    }
    log.debug("Structured JSON logback appender '{}' installed", appenderName);
  }
}
