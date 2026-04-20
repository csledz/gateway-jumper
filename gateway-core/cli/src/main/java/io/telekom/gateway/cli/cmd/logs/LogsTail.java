// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.cmd.logs;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.telekom.gateway.cli.cmd.ParentOptions;
import io.telekom.gateway.cli.config.KubeconfigLoader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code gatectl logs POD} — stream container logs for a proxy pod. Supports {@code --follow}
 * (fabric8's {@code LogWatch}) and a client-side {@code --grep} regex filter applied line by line.
 */
@Command(
    name = "logs",
    description = "Stream logs from a proxy pod.",
    mixinStandardHelpOptions = true)
public final class LogsTail implements Callable<Integer> {

  @Parameters(paramLabel = "POD", description = "Pod name (or label selector via --selector).")
  String pod;

  @Option(
      names = {"-f", "--follow"},
      description = "Follow log output (like kubectl logs -f).",
      defaultValue = "false")
  boolean follow;

  @Option(
      names = {"-c", "--container"},
      description = "Container name when the pod has multiple containers.")
  String container;

  @Option(
      names = {"--tail"},
      description = "Number of lines to show from the end of the logs. -1 = all (default).",
      defaultValue = "-1")
  int tailLines;

  @Option(
      names = {"--grep"},
      description = "Only print lines matching this (Java) regex.")
  String grep;

  @Spec private CommandSpec spec;

  private final KubeconfigLoader kubeconfigLoader;

  public LogsTail() {
    this(new KubeconfigLoader());
  }

  public LogsTail(KubeconfigLoader kubeconfigLoader) {
    this.kubeconfigLoader = kubeconfigLoader;
  }

  @Override
  public Integer call() {
    String namespace = ParentOptions.namespace(spec);
    PrintWriter out = this.spec.commandLine().getOut();
    PrintWriter err = this.spec.commandLine().getErr();
    Pattern pattern = grep == null || grep.isBlank() ? null : Pattern.compile(grep);

    try (KubernetesClient client =
        kubeconfigLoader.load(ParentOptions.kubeContext(spec), namespace)) {
      PodResource podResource = client.pods().inNamespace(namespace).withName(pod);
      Pod resolved = podResource.get();
      if (resolved == null) {
        err.printf("Pod %s/%s not found%n", namespace, pod);
        return 1;
      }

      if (follow) {
        return stream(out, podResource, pattern);
      }
      String logs;
      if (tailLines > 0) {
        logs =
            container != null && !container.isBlank()
                ? podResource.inContainer(container).tailingLines(tailLines).getLog()
                : podResource.tailingLines(tailLines).getLog();
      } else {
        logs =
            container != null && !container.isBlank()
                ? podResource.inContainer(container).getLog()
                : podResource.getLog();
      }
      if (logs == null) {
        return 0;
      }
      for (String line : splitLines(logs)) {
        if (pattern == null || pattern.matcher(line).find()) {
          out.println(line);
        }
      }
      out.flush();
    }
    return 0;
  }

  private int stream(PrintWriter out, PodResource podResource, Pattern pattern) {
    PodResource scoped =
        container != null && !container.isBlank()
            ? (PodResource) podResource.inContainer(container)
            : podResource;
    try (LogWatch watch = scoped.watchLog();
        InputStream in = watch.getOutput();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (pattern == null || pattern.matcher(line).find()) {
          out.println(line);
          out.flush();
        }
      }
    } catch (Exception e) {
      this.spec.commandLine().getErr().println("log stream closed: " + e.getMessage());
      return 1;
    }
    return 0;
  }

  private static List<String> splitLines(String raw) {
    return List.of(raw.split("\\R", -1));
  }
}
