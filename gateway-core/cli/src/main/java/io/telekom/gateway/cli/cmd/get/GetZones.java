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

/** {@code gatectl get zones} — list Zone CRDs. */
@Command(
    name = "get-zones",
    aliases = {"zones"},
    description = "List gateway zones.",
    mixinStandardHelpOptions = true)
public final class GetZones implements Callable<Integer> {

  private static final List<String> COLUMNS =
      List.of("NAMESPACE", "NAME", "URL", "REGION", "STATUS");

  @Mixin OutputOption outputOption = new OutputOption();

  @Option(
      names = {"-A", "--all-namespaces"},
      description = "List zones across all namespaces.")
  boolean allNamespaces;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public GetZones() {
    this(new KubeconfigLoader());
  }

  public GetZones(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    return ListResource.run(
        spec,
        kubeconfigLoader,
        outputOption,
        allNamespaces,
        GatewayResources.KIND_ZONE,
        GatewayResources.PLURAL_ZONES,
        COLUMNS,
        (r, row) -> {
          var crSpec = GatewayResources.spec(r);
          row.put("URL", crSpec.getOrDefault("url", ""));
          row.put("REGION", crSpec.getOrDefault("region", ""));
        });
  }
}
