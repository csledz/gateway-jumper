/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.loader;

import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Discovers and (re)loads {@link GatewayPlugin}s via {@link ServiceLoader}.
 *
 * <p>Two sources:
 *
 * <ol>
 *   <li>The current {@link Thread#getContextClassLoader() thread context classloader} — this picks
 *       up plugins bundled in the application.
 *   <li>An optional <em>plugin directory</em> — every {@code *.jar} in that directory is loaded
 *       into a child {@link URLClassLoader}. When {@link #startWatching()} is invoked, file
 *       creations / modifications / deletions in that directory trigger a reload on a daemon
 *       thread.
 * </ol>
 *
 * <p>Duplicate {@link GatewayPlugin#name() names} are de-duplicated: the first-seen wins and a
 * warning is logged.
 *
 * <p>This class is thread-safe; reloads publish a new immutable snapshot to the {@link
 * PluginRegistry}.
 */
@Slf4j
public class PluginLoader implements AutoCloseable {

  private final Path pluginDir;
  private final PluginRegistry registry;
  private final CopyOnWriteArrayList<URLClassLoader> openClassLoaders =
      new CopyOnWriteArrayList<>();

  private volatile WatchService watchService;
  private volatile Thread watchThread;
  private volatile boolean running;

  public PluginLoader(Path pluginDir, PluginRegistry registry) {
    this.pluginDir = pluginDir;
    this.registry = registry;
  }

  /** Performs an initial scan and populates the registry. */
  public List<GatewayPlugin> loadAll() {
    List<GatewayPlugin> loaded = new ArrayList<>();
    loaded.addAll(loadFromClasspath());
    loaded.addAll(loadFromDirectory());

    // De-duplicate by name (first wins); the registry sorts by order on publication.
    Map<String, GatewayPlugin> byName = new LinkedHashMap<>();
    for (GatewayPlugin p : loaded) {
      GatewayPlugin incumbent = byName.putIfAbsent(p.name(), p);
      if (incumbent != null) {
        log.warn(
            "Duplicate plugin name '{}' — keeping first-seen ({}), ignoring {}",
            p.name(),
            incumbent.getClass().getName(),
            p.getClass().getName());
      }
    }
    registry.replaceAll(byName.values());
    List<GatewayPlugin> published = registry.all();
    log.info(
        "Loaded {} plugin(s): {}",
        published.size(),
        published.stream().map(GatewayPlugin::name).toList());
    return published;
  }

  private List<GatewayPlugin> loadFromClasspath() {
    List<GatewayPlugin> result = new ArrayList<>();
    try {
      ServiceLoader<GatewayPlugin> sl =
          ServiceLoader.load(GatewayPlugin.class, Thread.currentThread().getContextClassLoader());
      for (GatewayPlugin p : sl) {
        result.add(p);
      }
    } catch (ServiceConfigurationError e) {
      log.error("ServiceLoader failure on classpath scan", e);
    }
    return result;
  }

  private List<GatewayPlugin> loadFromDirectory() {
    List<GatewayPlugin> result = new ArrayList<>();
    if (pluginDir == null || !Files.isDirectory(pluginDir)) {
      log.debug("Plugin directory {} does not exist — skipping", pluginDir);
      return result;
    }

    // Close previous generation of plugin classloaders so hot-reload doesn't leak.
    closeOpenClassLoaders();

    List<URL> jarUrls = new ArrayList<>();
    try (Stream<Path> files = Files.list(pluginDir)) {
      files
          .filter(p -> p.getFileName().toString().endsWith(".jar"))
          .forEach(
              p -> {
                try {
                  jarUrls.add(p.toUri().toURL());
                } catch (IOException ioe) {
                  log.warn("Skipping unreadable jar {}: {}", p, ioe.getMessage());
                }
              });
    } catch (IOException e) {
      log.error("Failed to list plugin directory {}", pluginDir, e);
      return result;
    }

    if (jarUrls.isEmpty()) {
      return result;
    }

    URLClassLoader cl =
        new URLClassLoader(
            jarUrls.toArray(new URL[0]), Thread.currentThread().getContextClassLoader());
    openClassLoaders.add(cl);

    try {
      // Only accept plugins defined by the plugin jars themselves; anything reachable via the
      // parent classloader was already picked up by loadFromClasspath() and would double up.
      ServiceLoader<GatewayPlugin> sl = ServiceLoader.load(GatewayPlugin.class, cl);
      for (GatewayPlugin p : sl) {
        if (p.getClass().getClassLoader() == cl) {
          result.add(p);
        }
      }
    } catch (ServiceConfigurationError e) {
      log.error("ServiceLoader failure on plugin directory scan", e);
    }
    return result;
  }

  /** Start the directory watcher. No-op if the plugin dir is not a directory. */
  public synchronized void startWatching() {
    if (running) {
      return;
    }
    if (pluginDir == null || !Files.isDirectory(pluginDir)) {
      log.debug("Not starting watcher — plugin directory {} is not a directory", pluginDir);
      return;
    }
    try {
      this.watchService = FileSystems.getDefault().newWatchService();
      pluginDir.register(
          watchService,
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_DELETE);
    } catch (IOException e) {
      log.error("Failed to register watch for {}", pluginDir, e);
      return;
    }

    this.running = true;
    this.watchThread = new Thread(this::watchLoop, "plugin-spi-watcher-" + pluginDir.getFileName());
    this.watchThread.setDaemon(true);
    this.watchThread.start();
    log.info("Plugin directory watcher started on {}", pluginDir);
  }

  private void watchLoop() {
    while (running) {
      WatchKey key;
      try {
        key = watchService.poll(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException cwse) {
        return;
      }
      if (key == null) {
        continue;
      }
      boolean sawChange = !key.pollEvents().isEmpty();
      key.reset();
      if (sawChange) {
        // Tiny debounce so we don't reload mid-copy of a jar.
        try {
          Thread.sleep(200);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
        try {
          log.info("Plugin directory changed — reloading");
          loadAll();
        } catch (RuntimeException re) {
          log.error("Plugin reload failed", re);
        }
      }
    }
  }

  private void closeOpenClassLoaders() {
    for (URLClassLoader cl : openClassLoaders) {
      try {
        cl.close();
      } catch (IOException e) {
        log.debug("Failed to close previous plugin classloader", e);
      }
    }
    openClassLoaders.clear();
  }

  @Override
  public synchronized void close() {
    running = false;
    if (watchService != null) {
      try {
        watchService.close();
      } catch (IOException e) {
        log.debug("Failed closing watch service", e);
      }
    }
    if (watchThread != null) {
      watchThread.interrupt();
    }
    closeOpenClassLoaders();
  }
}
