// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.describe;

import io.telekom.gateway.cli.config.KubeconfigLoader;
import io.telekom.gateway.cli.k8s.GatewayResources;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code gatectl describe route NAME} — detailed view of a single Route CRD. */
@Command(
    name = "describe-route",
    aliases = {"describe-routes"},
    description = "Describe a gateway route.",
    mixinStandardHelpOptions = true)
public final class DescribeRoute implements Callable<Integer> {

  @Parameters(paramLabel = "NAME", description = "Name of the route to describe.")
  String name;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public DescribeRoute() {
    this(new KubeconfigLoader());
  }

  public DescribeRoute(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    return DescribeResource.run(
        spec,
        kubeconfigLoader,
        GatewayResources.KIND_ROUTE,
        GatewayResources.PLURAL_ROUTES,
        name,
        null);
  }
}
