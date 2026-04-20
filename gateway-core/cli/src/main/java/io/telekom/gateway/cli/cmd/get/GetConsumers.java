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

/** {@code gatectl get consumers} — list Consumer CRDs. */
@Command(
    name = "get-consumers",
    aliases = {"consumers"},
    description = "List gateway consumers.",
    mixinStandardHelpOptions = true)
public final class GetConsumers implements Callable<Integer> {

  private static final List<String> COLUMNS =
      List.of("NAMESPACE", "NAME", "CLIENT_ID", "REALM", "STATUS");

  @Mixin OutputOption outputOption = new OutputOption();

  @Option(
      names = {"-A", "--all-namespaces"},
      description = "List consumers across all namespaces.")
  boolean allNamespaces;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public GetConsumers() {
    this(new KubeconfigLoader());
  }

  public GetConsumers(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    return ListResource.run(
        spec,
        kubeconfigLoader,
        outputOption,
        allNamespaces,
        GatewayResources.KIND_CONSUMER,
        GatewayResources.PLURAL_CONSUMERS,
        COLUMNS,
        (r, row) -> {
          var crSpec = GatewayResources.spec(r);
          row.put("CLIENT_ID", crSpec.getOrDefault("clientId", ""));
          row.put("REALM", crSpec.getOrDefault("realm", ""));
        });
  }
}
