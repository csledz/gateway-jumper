// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.emit;

import io.telekom.gateway.migration_tool.model.CrdResource;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Emits one YAML file per kind under the output directory.
 *
 * <p>Within each file, resources are separated by {@code ---}. Output is deterministic: resources
 * are emitted in the order they appear in the input list, with fields in insertion order.
 */
@Slf4j
public final class CrdYamlWriter {

  private final Yaml yaml;

  public CrdYamlWriter() {
    DumperOptions opts = new DumperOptions();
    opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    opts.setPrettyFlow(true);
    opts.setIndent(2);
    opts.setWidth(120);
    opts.setExplicitStart(false);
    this.yaml = new Yaml(opts);
  }

  public void writeAll(List<CrdResource> resources, Path outputDir) throws IOException {
    Files.createDirectories(outputDir);

    // group by kind to produce one file per kind (more pleasant to apply with kubectl)
    Map<String, List<CrdResource>> byKind = new LinkedHashMap<>();
    for (CrdResource r : resources) {
      byKind.computeIfAbsent(r.getKind(), k -> new java.util.ArrayList<>()).add(r);
    }

    for (Map.Entry<String, List<CrdResource>> e : byKind.entrySet()) {
      Path file = outputDir.resolve(fileName(e.getKey()));
      try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        writeKindFile(w, e.getValue());
      }
      log.info("wrote {} {} resources to {}", e.getValue().size(), e.getKey(), file);
    }
  }

  private void writeKindFile(Writer w, List<CrdResource> resources) throws IOException {
    boolean first = true;
    for (CrdResource r : resources) {
      if (!first) {
        w.write("---\n");
      }
      first = false;
      yaml.dump(toMap(r), w);
    }
  }

  private static Map<String, Object> toMap(CrdResource r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("apiVersion", r.getApiVersion());
    m.put("kind", r.getKind());
    m.put("metadata", r.getMetadata());
    if (r.getStringData() != null && !r.getStringData().isEmpty()) {
      // k8s Secret: use stringData, drop spec (Secrets don't have spec).
      m.put("type", "Opaque");
      m.put("stringData", r.getStringData());
    } else {
      m.put("spec", r.getSpec());
    }
    return m;
  }

  private static String fileName(String kind) {
    // GatewayRoute -> gatewayroute.yaml
    return kind.toLowerCase() + ".yaml";
  }
}
