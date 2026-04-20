/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.config;

import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import io.telekom.gateway.plugin_spi.filter.PluginDispatchFilter;
import io.telekom.gateway.plugin_spi.loader.PluginLoader;
import io.telekom.gateway.plugin_spi.loader.PluginRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the SPI loader / registry / dispatch filter when {@code gateway.plugin-spi.enabled=true}.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PluginSpiProperties.class)
@ConditionalOnProperty(
    prefix = "gateway.plugin-spi",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PluginSpiAutoConfiguration {

  @Bean
  public PluginRegistry pluginRegistry() {
    return new PluginRegistry();
  }

  @Bean(destroyMethod = "close")
  public PluginLoader pluginLoader(PluginSpiProperties props, PluginRegistry registry) {
    Path dir = props.getDirectory() == null ? null : Path.of(props.getDirectory());
    PluginLoader loader = new PluginLoader(dir, registry);
    List<GatewayPlugin> loaded = loader.loadAll();
    filterEnabled(registry, props, loaded);
    if (props.isWatch()) {
      loader.startWatching();
    }
    return loader;
  }

  @Bean
  public PluginDispatchFilter pluginDispatchFilter(PluginRegistry registry) {
    return new PluginDispatchFilter(registry);
  }

  private void filterEnabled(
      PluginRegistry registry, PluginSpiProperties props, List<GatewayPlugin> all) {
    List<String> allowList = props.getEnabledPlugins();
    if (allowList == null || allowList.isEmpty()) {
      return;
    }
    Set<String> allowed = new HashSet<>(allowList);
    Set<String> keptNames = new HashSet<>();
    List<GatewayPlugin> kept = new ArrayList<>();
    for (GatewayPlugin p : all) {
      if (allowed.contains(p.name())) {
        kept.add(p);
        keptNames.add(p.name());
      } else {
        log.info("Disabling plugin '{}' (not in gateway.plugin-spi.enabled-plugins)", p.name());
      }
    }
    for (String requested : allowList) {
      if (!keptNames.contains(requested)) {
        log.warn(
            "Configured plugin '{}' is not available on the classpath / plugin dir", requested);
      }
    }
    registry.replaceAll(kept);
  }
}
