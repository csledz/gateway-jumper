// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.get;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.telekom.gateway.cli.cmd.ParentOptions;
import io.telekom.gateway.cli.config.KubeconfigLoader;
import io.telekom.gateway.cli.k8s.GatewayResources;
import io.telekom.gateway.cli.output.Printer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Shared flow for every kubectl-style {@code get-*} subcommand: load resources of one kind, invoke
 * a per-command row builder, and write them through the chosen {@link Printer}.
 */
final class ListResource {

  private ListResource() {}

  static int run(
      CommandSpec spec,
      KubeconfigLoader kubeconfigLoader,
      OutputOption outputOption,
      boolean allNamespaces,
      String kind,
      String plural,
      List<String> columns,
      BiConsumer<GenericKubernetesResource, Map<String, Object>> rowBuilder) {
    String namespace = allNamespaces ? null : ParentOptions.namespace(spec);
    try (KubernetesClient client =
        kubeconfigLoader.load(ParentOptions.kubeContext(spec), namespace)) {
      List<GenericKubernetesResource> items =
          GatewayResources.list(client, namespace, kind, plural);
      List<Map<String, Object>> rows = new ArrayList<>(items.size());
      for (GenericKubernetesResource r : items) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("NAMESPACE", r.getMetadata() == null ? "" : r.getMetadata().getNamespace());
        row.put("NAME", GatewayResources.name(r));
        rowBuilder.accept(r, row);
        row.putIfAbsent("STATUS", GatewayResources.status(r).getOrDefault("phase", "Unknown"));
        rows.add(row);
      }
      Printer.of(outputOption.format()).printList(spec.commandLine().getOut(), columns, rows);
    }
    return 0;
  }
}
