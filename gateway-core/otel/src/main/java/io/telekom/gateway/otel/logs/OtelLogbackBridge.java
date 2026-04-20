// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Programmatically attaches an {@link OpenTelemetryAppender} to the Logback root logger so log
 * records flow through the OTel logs pipeline alongside traces and metrics.
 *
 * <p>Gated on {@code gateway.otel.logs.enabled=true} — off by default so existing modules that only
 * want traces + metrics pay nothing for logs. When on, the appender is installed on start and
 * removed on shutdown so the bridge is idempotent across context refreshes.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.otel.logs", name = "enabled", havingValue = "true")
public class OtelLogbackBridge {

  static final String APPENDER_NAME = "gateway-core-otel-logs";

  private final ObjectProvider<OpenTelemetry> openTelemetryProvider;
  private volatile OpenTelemetryAppender appender;

  public OtelLogbackBridge(ObjectProvider<OpenTelemetry> openTelemetryProvider) {
    this.openTelemetryProvider = openTelemetryProvider;
  }

  @PostConstruct
  public void install() {
    OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
    if (openTelemetry == null) {
      log.warn("OTel logs bridge requested but no OpenTelemetry bean present — skipping install");
      return;
    }

    org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (!(factory instanceof LoggerContext loggerContext)) {
      log.warn(
          "OTel logs bridge requested but SLF4J is not bound to Logback ({}); skipping install",
          factory.getClass().getName());
      return;
    }

    OpenTelemetryAppender openTelemetryAppender = new OpenTelemetryAppender();
    openTelemetryAppender.setName(APPENDER_NAME);
    openTelemetryAppender.setContext(loggerContext);
    openTelemetryAppender.setCaptureExperimentalAttributes(false);
    openTelemetryAppender.setCaptureCodeAttributes(false);
    openTelemetryAppender.setCaptureMdcAttributes("*");
    openTelemetryAppender.setCaptureKeyValuePairAttributes(true);
    openTelemetryAppender.setOpenTelemetry(openTelemetry);
    openTelemetryAppender.start();

    Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    // Guard against double-install on context refresh.
    Appender<ch.qos.logback.classic.spi.ILoggingEvent> existing = root.getAppender(APPENDER_NAME);
    if (existing != null) {
      existing.stop();
      root.detachAppender(existing);
    }
    root.addAppender(openTelemetryAppender);

    this.appender = openTelemetryAppender;
    log.info("OTel Logback appender '{}' attached to root logger", APPENDER_NAME);
  }

  @PreDestroy
  public void remove() {
    OpenTelemetryAppender current = this.appender;
    if (current == null) {
      return;
    }
    org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext loggerContext) {
      Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
      root.detachAppender(current);
      current.stop();
    }
    this.appender = null;
  }

  OpenTelemetryAppender getAppender() {
    return appender;
  }
}
