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

/** {@code gatectl describe consumer NAME} — detailed view of one Consumer CRD. */
@Command(
    name = "describe-consumer",
    aliases = {"describe-consumers"},
    description = "Describe a gateway consumer.",
    mixinStandardHelpOptions = true)
public final class DescribeConsumer implements Callable<Integer> {

  @Parameters(paramLabel = "NAME", description = "Name of the consumer to describe.")
  String name;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public DescribeConsumer() {
    this(new KubeconfigLoader());
  }

  public DescribeConsumer(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    return DescribeResource.run(
        spec,
        kubeconfigLoader,
        GatewayResources.KIND_CONSUMER,
        GatewayResources.PLURAL_CONSUMERS,
        name,
        null);
  }
}
