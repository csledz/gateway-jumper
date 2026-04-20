// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.get;

import io.telekom.gateway.cli.config.KubeconfigLoader;
import io.telekom.gateway.cli.k8s.GatewayResources;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code gatectl get routes} — list Route CRDs kubectl-style. */
@Command(
    name = "get-routes",
    aliases = {"routes"},
    description = "List gateway routes.",
    mixinStandardHelpOptions = true)
public final class GetRoutes implements Callable<Integer> {

  private static final List<String> COLUMNS =
      List.of("NAMESPACE", "NAME", "HOST", "PATH", "UPSTREAM", "STATUS");

  @Mixin OutputOption outputOption = new OutputOption();

  @Option(
      names = {"-A", "--all-namespaces"},
      description = "List routes across all namespaces.")
  boolean allNamespaces;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public GetRoutes() {
    this(new KubeconfigLoader());
  }

  public GetRoutes(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    return ListResource.run(
        spec,
        kubeconfigLoader,
        outputOption,
        allNamespaces,
        GatewayResources.KIND_ROUTE,
        GatewayResources.PLURAL_ROUTES,
        COLUMNS,
        (r, row) -> {
          var crSpec = GatewayResources.spec(r);
          row.put("HOST", crSpec.getOrDefault("host", ""));
          row.put("PATH", crSpec.getOrDefault("path", ""));
          row.put("UPSTREAM", crSpec.getOrDefault("upstream", ""));
        });
  }
}
