// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.describe;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.telekom.gateway.cli.cmd.ParentOptions;
import io.telekom.gateway.cli.config.KubeconfigLoader;
import io.telekom.gateway.cli.k8s.GatewayResources;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import picocli.CommandLine.Model.CommandSpec;

/** Shared describe flow: load a CRD by name, render Name/Namespace/Spec/Status + any extras. */
final class DescribeResource {

  private DescribeResource() {}

  /**
   * @param extras optional builder for format-specific sections (e.g. the Zone's live health);
   *     receives the mutable {@code view} map so callers can append entries after the defaults.
   */
  static int run(
      CommandSpec spec,
      KubeconfigLoader kubeconfigLoader,
      String kind,
      String plural,
      String name,
      Consumer<Map<String, Object>> extras) {
    String namespace = ParentOptions.namespace(spec);
    try (KubernetesClient client =
        kubeconfigLoader.load(ParentOptions.kubeContext(spec), namespace)) {
      GenericKubernetesResource r = GatewayResources.get(client, namespace, kind, plural, name);
      PrintWriter out = spec.commandLine().getOut();
      if (r == null) {
        spec.commandLine().getErr().printf("%s %s/%s not found%n", kind, namespace, name);
        return 1;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("Name", GatewayResources.name(r));
      view.put("Namespace", r.getMetadata() == null ? "" : r.getMetadata().getNamespace());
      view.put("Spec", GatewayResources.spec(r));
      view.put("Status", GatewayResources.status(r));
      if (extras != null) {
        extras.accept(view);
      }
      DescribeRenderer.render(out, view);
    }
    return 0;
  }
}
