// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads a fabric8 {@link KubernetesClient} from {@code ~/.kube/config} with optional context /
 * namespace overrides. Respects the {@code KUBECONFIG} env var if set (fabric8 default).
 *
 * <p>The loader is intentionally tiny — there's no need to replicate kubectl's full resolution. We
 * lean on fabric8 autoConfigure for the heavy lifting and only layer in the overrides.
 */
@Slf4j
public class KubeconfigLoader {

  /**
   * Build a client.
   *
   * @param context optional context name; when {@code null} or blank the current-context is used.
   * @param namespace optional namespace override; when {@code null} or blank the context default is
   *     kept.
   */
  public KubernetesClient load(String context, String namespace) {
    ConfigBuilder builder = new ConfigBuilder(Config.autoConfigure(blankToNull(context)));
    if (!isBlank(namespace)) {
      builder.withNamespace(namespace);
    }
    Config cfg = builder.build();
    log.debug(
        "Loaded kubeconfig: context={}, namespace={}, master={}",
        Objects.toString(context, "<current>"),
        cfg.getNamespace(),
        cfg.getMasterUrl());
    return new KubernetesClientBuilder().withConfig(cfg).build();
  }

  /** Build a client against a pre-built {@link Config} — used by tests to inject fabric8-mock. */
  public KubernetesClient loadFromConfig(Config cfg) {
    return new KubernetesClientBuilder().withConfig(cfg).build();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String blankToNull(String s) {
    return isBlank(s) ? null : s;
  }
}
