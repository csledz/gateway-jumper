/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.steps;

import io.telekom.gateway.plugin_spi.api.PipelineStage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Utility that produces a plugin-JAR on disk with a <em>freshly-compiled</em> {@code GatewayPlugin}
 * implementation. A unique package (based on the plugin name) ensures the class is not present on
 * the parent test classpath, so the loader's child-classloader path exercises correctly.
 */
public final class TestJarBuilder {

  private TestJarBuilder() {}

  public static Path build(Path dir, String pluginName, PipelineStage stage, int order)
      throws IOException {
    String pkg = "io.telekom.gateway.plugin_spi.generated." + sanitize(pluginName);
    String simple = "GeneratedPlugin";
    String fqn = pkg + "." + simple;
    String source = generateSource(pkg, simple, pluginName, stage, order);

    Map<String, byte[]> classes = compile(fqn, source);
    if (classes.isEmpty()) {
      throw new IOException("In-memory compile produced no classes for " + fqn);
    }

    Path jar = dir.resolve(sanitize(pluginName) + "-plugin-" + System.nanoTime() + ".jar");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
      for (Map.Entry<String, byte[]> e : classes.entrySet()) {
        String entryPath = e.getKey().replace('.', '/') + ".class";
        jos.putNextEntry(new JarEntry(entryPath));
        jos.write(e.getValue());
        jos.closeEntry();
      }
      jos.putNextEntry(
          new JarEntry("META-INF/services/io.telekom.gateway.plugin_spi.api.GatewayPlugin"));
      jos.write((fqn + "\n").getBytes(StandardCharsets.UTF_8));
      jos.closeEntry();
    }
    return jar;
  }

  private static String sanitize(String s) {
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
    }
    String out = sb.toString();
    if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) {
      out = "p_" + out;
    }
    return out.toLowerCase(Locale.ROOT);
  }

  private static String generateSource(
      String pkg, String simple, String pluginName, PipelineStage stage, int order) {
    return "package "
        + pkg
        + ";\n"
        + "import io.telekom.gateway.plugin_spi.api.GatewayPlugin;\n"
        + "import io.telekom.gateway.plugin_spi.api.PipelineStage;\n"
        + "import io.telekom.gateway.plugin_spi.api.PluginContext;\n"
        + "import reactor.core.publisher.Mono;\n"
        + "public class "
        + simple
        + " implements GatewayPlugin {\n"
        + "  public String name() { return \""
        + pluginName
        + "\"; }\n"
        + "  public int order() { return "
        + order
        + "; }\n"
        + "  public PipelineStage stage() { return PipelineStage."
        + stage.name()
        + "; }\n"
        + "  public Mono<Void> apply(PluginContext c) { return Mono.empty(); }\n"
        + "}\n";
  }

  private static Map<String, byte[]> compile(String fqn, String source) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IOException("No system Java compiler — run with JDK, not JRE");
    }
    DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
    try (StandardJavaFileManager std =
        compiler.getStandardFileManager(diag, null, StandardCharsets.UTF_8)) {
      InMemoryFileManager mgr = new InMemoryFileManager(std);
      JavaFileObject src =
          new SimpleJavaFileObject(
              URI.create("string:///" + fqn.replace('.', '/') + ".java"),
              JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return source;
            }
          };
      List<String> options =
          List.of(
              "-source",
              "21",
              "-target",
              "21",
              "-classpath",
              System.getProperty("java.class.path"));
      JavaCompiler.CompilationTask task =
          compiler.getTask(null, mgr, diag, options, null, List.of(src));
      boolean ok = task.call();
      if (!ok) {
        StringBuilder sb = new StringBuilder("Compile failed:\n");
        for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
          sb.append(d).append('\n');
        }
        throw new IOException(sb.toString());
      }
      return mgr.classes();
    }
  }

  private static final class InMemoryFileManager
      extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, ByteArrayOutputStream> out = new HashMap<>();

    InMemoryFileManager(StandardJavaFileManager fm) {
      super(fm);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
      return new SimpleJavaFileObject(
          URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
        @Override
        public java.io.OutputStream openOutputStream() {
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          out.put(className, baos);
          return baos;
        }
      };
    }

    Map<String, byte[]> classes() {
      Map<String, byte[]> m = new HashMap<>();
      out.forEach((k, v) -> m.put(k, v.toByteArray()));
      return m;
    }
  }
}
