// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.mapping;

import io.telekom.gateway.migration_tool.model.KongPlugin;
import io.telekom.gateway.migration_tool.model.MigrationResult;
import io.telekom.gateway.migration_tool.model.UnmigratedEntry;
import java.util.List;
import java.util.Map;

/**
 * Known unmigrated Kong plugins with documented reasons.
 *
 * <p>This list is informational: the actual detection of unmigrated plugins happens in {@code
 * KongToCrdMapper} (anything we do not have a mapping for lands in the unmigrated list). The
 * reasons here are used when producing the human-readable diff report.
 */
public final class UnmigratedReport {

  /** Reasons keyed by Kong plugin name. */
  public static final Map<String, String> REASONS =
      Map.ofEntries(
          Map.entry(
              "prometheus",
              "Replaced by built-in Micrometer/Prometheus metrics exporter in gateway-core; no CRD"
                  + " mapping required. Enable via actuator config."),
          Map.entry(
              "ip-restriction",
              "Gateway-core does not yet expose an IP allow/deny-list policy; handle at the ingress"
                  + " or service-mesh layer for now."),
          Map.entry(
              "pre-function",
              "Kong-specific Wasm/Lua hook with no safe gateway-core equivalent; rewrite as a"
                  + " plugin-spi filter."),
          Map.entry(
              "post-function",
              "Kong-specific Wasm/Lua hook with no safe gateway-core equivalent; rewrite as a"
                  + " plugin-spi filter."),
          Map.entry(
              "wasm",
              "Arbitrary Wasm modules are not executed by gateway-core; port the logic to a"
                  + " JVM-based plugin-spi filter."),
          Map.entry(
              "bot-detection",
              "No direct equivalent; evaluate WAF / edge proxy (e.g. nginx, Envoy RateLimit"
                  + " service)."),
          Map.entry(
              "acl",
              "Consumer groups / ACLs are not yet modelled in gateway-core CRDs; tracked"
                  + " separately."));

  private UnmigratedReport() {}

  public static UnmigratedEntry describe(KongPlugin p) {
    String reason =
        REASONS.getOrDefault(
            p.getName(),
            "No mapping defined for plugin '"
                + p.getName()
                + "'. Review manually and port to plugin-spi if needed.");
    return new UnmigratedEntry(p.getName(), p.getScope(), p.getScopeRef(), reason);
  }

  /** Render the unmigrated entries as a human-readable report. */
  public static String render(MigrationResult result) {
    List<UnmigratedEntry> entries = result.getUnmigrated();
    if (entries.isEmpty()) {
      return "All Kong plugins mapped successfully.\n";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Unmigrated Kong plugins (").append(entries.size()).append("):\n");
    for (UnmigratedEntry e : entries) {
      sb.append("  - ").append(e.getPluginName());
      if (e.getScope() != null) {
        sb.append(" [scope=").append(e.getScope());
        if (e.getScopeRef() != null) {
          sb.append(", ref=").append(e.getScopeRef());
        }
        sb.append("]");
      }
      sb.append("\n      ").append(e.getReason()).append('\n');
    }
    return sb.toString();
  }
}
