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

/** {@code gatectl get mesh-peers} — list federated mesh peers. */
@Command(
    name = "get-mesh-peers",
    aliases = {"mesh-peers", "peers"},
    description = "List gateway mesh peers.",
    mixinStandardHelpOptions = true)
public final class GetMeshPeers implements Callable<Integer> {

  private static final List<String> COLUMNS =
      List.of("NAMESPACE", "NAME", "ENDPOINT", "ZONE", "STATUS");

  @Mixin OutputOption outputOption = new OutputOption();

  @Option(
      names = {"-A", "--all-namespaces"},
      description = "List mesh peers across all namespaces.")
  boolean allNamespaces;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public GetMeshPeers() {
    this(new KubeconfigLoader());
  }

  public GetMeshPeers(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    return ListResource.run(
        spec,
        kubeconfigLoader,
        outputOption,
        allNamespaces,
        GatewayResources.KIND_MESH_PEER,
        GatewayResources.PLURAL_MESH_PEERS,
        COLUMNS,
        (r, row) -> {
          var crSpec = GatewayResources.spec(r);
          row.put("ENDPOINT", crSpec.getOrDefault("endpoint", ""));
          row.put("ZONE", crSpec.getOrDefault("zone", ""));
        });
  }
}
