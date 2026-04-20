// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.steps;

import io.telekom.gateway.cli.GatectlApp;
import io.telekom.gateway.cli.cmd.describe.DescribeConsumer;
import io.telekom.gateway.cli.cmd.describe.DescribeRoute;
import io.telekom.gateway.cli.cmd.describe.DescribeZone;
import io.telekom.gateway.cli.cmd.get.GetConsumers;
import io.telekom.gateway.cli.cmd.get.GetMeshPeers;
import io.telekom.gateway.cli.cmd.get.GetRoutes;
import io.telekom.gateway.cli.cmd.get.GetZones;
import io.telekom.gateway.cli.cmd.health.HealthCheck;
import io.telekom.gateway.cli.cmd.logs.LogsTail;
import io.telekom.gateway.cli.config.KubeconfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import picocli.CommandLine;

/**
 * Helper that wires a {@link CommandLine} with mock-aware subcommand instances. Tests drive the CLI
 * through this instead of {@link GatectlApp#main(String[])} so they can inject the fabric8 mock
 * client and the admin MockServer base URL.
 */
final class CliDriver {

  private CliDriver() {}

  static int exec(World world, String argsLine) {
    String[] args = argsLine.trim().isEmpty() ? new String[0] : argsLine.trim().split("\\s+");
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    try (PrintWriter outPw = new PrintWriter(out);
        PrintWriter errPw = new PrintWriter(err)) {
      KubeconfigLoader loader = world.kubeconfigLoader();
      CommandLine.IFactory factory =
          new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
              if (cls == GetRoutes.class) return cls.cast(new GetRoutes(loader));
              if (cls == GetZones.class) return cls.cast(new GetZones(loader));
              if (cls == GetConsumers.class) return cls.cast(new GetConsumers(loader));
              if (cls == GetMeshPeers.class) return cls.cast(new GetMeshPeers(loader));
              if (cls == DescribeRoute.class) return cls.cast(new DescribeRoute(loader));
              if (cls == DescribeZone.class)
                return cls.cast(new DescribeZone(loader, world.adminClientFactory()));
              if (cls == DescribeConsumer.class) return cls.cast(new DescribeConsumer(loader));
              if (cls == LogsTail.class) return cls.cast(new LogsTail(loader));
              if (cls == HealthCheck.class)
                return cls.cast(new HealthCheck(world.adminClientFactory()));
              return CommandLine.defaultFactory().create(cls);
            }
          };
      CommandLine cli =
          new CommandLine(new GatectlApp(), factory)
              .setOut(outPw)
              .setErr(errPw)
              .setExecutionExceptionHandler(
                  (ex, commandLine, parseResult) -> {
                    errPw.println("gatectl: " + ex.getMessage());
                    return GatectlApp.EXIT_ERROR;
                  })
              .setParameterExceptionHandler(
                  (ex, params) -> {
                    ex.getCommandLine().getErr().println("gatectl: " + ex.getMessage());
                    ex.getCommandLine().usage(ex.getCommandLine().getErr());
                    return GatectlApp.EXIT_USAGE;
                  });
      int rc = cli.execute(args);
      outPw.flush();
      errPw.flush();
      world.exitCode = rc;
      world.stdout = out.toString();
      world.stderr = err.toString();
      return rc;
    }
  }
}
