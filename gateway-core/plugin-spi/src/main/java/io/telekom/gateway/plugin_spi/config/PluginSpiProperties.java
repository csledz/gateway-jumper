/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime configuration for the plugin SPI. */
@Data
@ConfigurationProperties(prefix = "gateway.plugin-spi")
public class PluginSpiProperties {

  /** Enable / disable the whole SPI at runtime. */
  private boolean enabled = true;

  /** Absolute path to the plugin drop-directory; may be null (classpath-only scan). */
  private String directory;

  /** Watch the plugin directory for hot-reload. */
  private boolean watch = true;

  /**
   * Names of plugins to enable. Empty list means "all discovered plugins". Names not present in the
   * discovered set are ignored with a warning.
   */
  private List<String> enabledPlugins = List.of();
}
