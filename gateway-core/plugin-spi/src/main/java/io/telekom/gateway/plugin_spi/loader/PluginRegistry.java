/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.loader;

import io.telekom.gateway.plugin_spi.api.GatewayPlugin;
import io.telekom.gateway.plugin_spi.api.PipelineStage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, hot-swappable registry of {@link GatewayPlugin}s.
 *
 * <p>Exposed as a Spring bean; the runtime calls {@link #byStage(PipelineStage)} on every request.
 * The registry holds an immutable snapshot keyed by {@link PipelineStage}; {@link
 * #replaceAll(Collection)} publishes a brand-new snapshot atomically.
 */
public class PluginRegistry {

  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

  /** Replace the current plugin set with the supplied collection (already de-duplicated). */
  public void replaceAll(Collection<GatewayPlugin> plugins) {
    List<GatewayPlugin> sorted = new ArrayList<>(plugins);
    sorted.sort(Comparator.comparingInt(GatewayPlugin::order));

    Map<PipelineStage, List<GatewayPlugin>> byStage = new EnumMap<>(PipelineStage.class);
    for (PipelineStage s : PipelineStage.values()) {
      byStage.put(s, new ArrayList<>());
    }
    for (GatewayPlugin p : sorted) {
      byStage.get(p.stage()).add(p);
    }
    byStage.replaceAll((stage, list) -> List.copyOf(list));

    snapshot.set(new Snapshot(List.copyOf(sorted), byStage));
  }

  /** Plugins registered at the given stage, ordered by {@link GatewayPlugin#order()}. */
  public List<GatewayPlugin> byStage(PipelineStage stage) {
    return snapshot.get().byStage().getOrDefault(stage, List.of());
  }

  /** All plugins currently registered, ordered by {@link GatewayPlugin#order()} globally. */
  public List<GatewayPlugin> all() {
    return snapshot.get().all();
  }

  /** Lookup a plugin by its unique {@link GatewayPlugin#name() name}. */
  public Optional<GatewayPlugin> byName(String name) {
    for (GatewayPlugin p : snapshot.get().all()) {
      if (p.name().equals(name)) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  private record Snapshot(
      List<GatewayPlugin> all, Map<PipelineStage, List<GatewayPlugin>> byStage) {
    static Snapshot empty() {
      Map<PipelineStage, List<GatewayPlugin>> empty = new EnumMap<>(PipelineStage.class);
      for (PipelineStage s : PipelineStage.values()) {
        empty.put(s, List.of());
      }
      return new Snapshot(List.of(), empty);
    }
  }
}
