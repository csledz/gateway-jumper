// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.describe;

import io.telekom.gateway.cli.admin.AdminClient;
import io.telekom.gateway.cli.admin.ZoneHealth;
import io.telekom.gateway.cli.cmd.ParentOptions;
import io.telekom.gateway.cli.config.KubeconfigLoader;
import io.telekom.gateway.cli.k8s.GatewayResources;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code gatectl describe zone NAME} — joins the Zone CRD with a live {@code /admin/zones/*} call
 * so operators see configuration and health side-by-side.
 */
@Command(
    name = "describe-zone",
    aliases = {"describe-zones"},
    description = "Describe a gateway zone including live health.",
    mixinStandardHelpOptions = true)
public final class DescribeZone implements Callable<Integer> {

  /** How long we wait for the admin API before giving up. */
  static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(5);

  @Parameters(paramLabel = "NAME", description = "Name of the zone to describe.")
  String name;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;
  private final Function<String, AdminClient> adminClientFactory;

  public DescribeZone() {
    this(new KubeconfigLoader(), AdminClient::new);
  }

  public DescribeZone(
      KubeconfigLoader kubeconfigLoader, Function<String, AdminClient> adminClientFactory) {
    this.kubeconfigLoader = kubeconfigLoader;
    this.adminClientFactory = adminClientFactory;
  }

  @Override
  public Integer call() {
    return DescribeResource.run(
        spec,
        kubeconfigLoader,
        GatewayResources.KIND_ZONE,
        GatewayResources.PLURAL_ZONES,
        name,
        view -> {
          AdminClient admin = adminClientFactory.apply(ParentOptions.adminUrl(spec));
          ZoneHealth health =
              admin
                  .zoneHealth(name)
                  .timeout(ADMIN_TIMEOUT)
                  .onErrorReturn(
                      new ZoneHealth(name, "UNKNOWN", "admin API unreachable", List.of()))
                  .block();

          var healthView = new LinkedHashMap<String, Object>();
          healthView.put("Status", health == null ? "UNKNOWN" : health.status());
          healthView.put("Message", health == null ? "" : health.message());
          if (health != null && !health.checks().isEmpty()) {
            healthView.put("Checks", health.checks());
          }
          view.put("Health", healthView);
        });
  }
}
