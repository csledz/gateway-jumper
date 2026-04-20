// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.kong;

import io.telekom.gateway.migration_tool.model.DeckConfig;
import io.telekom.gateway.migration_tool.model.KongConsumer;
import io.telekom.gateway.migration_tool.model.KongCredential;
import io.telekom.gateway.migration_tool.model.KongPlugin;
import io.telekom.gateway.migration_tool.model.KongRoute;
import io.telekom.gateway.migration_tool.model.KongService;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a Kong declarative (decK) YAML document into an in-memory {@link DeckConfig} tree.
 *
 * <p>Intentionally lenient: unknown top-level keys are ignored; missing optional fields default to
 * empty collections. We use SnakeYAML's SafeConstructor so untrusted inputs cannot instantiate
 * arbitrary classes.
 */
@Slf4j
public final class DeckReader {

  private static final String SCOPE_GLOBAL = "global";
  private static final String SCOPE_SERVICE = "service";
  private static final String SCOPE_ROUTE = "route";
  private static final String SCOPE_CONSUMER = "consumer";

  public DeckConfig read(Path yamlPath) throws IOException {
    log.debug("reading decK YAML from {}", yamlPath);
    try (InputStream in = Files.newInputStream(yamlPath)) {
      return read(in);
    }
  }

  public DeckConfig read(InputStream in) {
    return read(newYaml().<Map<String, Object>>load(in));
  }

  public DeckConfig read(Reader reader) {
    return read(newYaml().<Map<String, Object>>load(reader));
  }

  public DeckConfig read(String yamlText) {
    return read(newYaml().<Map<String, Object>>load(yamlText));
  }

  private static Yaml newYaml() {
    LoaderOptions opts = new LoaderOptions();
    opts.setAllowDuplicateKeys(false);
    opts.setMaxAliasesForCollections(50);
    return new Yaml(new SafeConstructor(opts));
  }

  @SuppressWarnings("unchecked")
  private DeckConfig read(Map<String, Object> root) {
    DeckConfig cfg = new DeckConfig();
    if (root == null) {
      return cfg;
    }
    cfg.setFormatVersion(asString(root.get("_format_version")));

    for (Map<String, Object> svc : asMapList(root.get("services"))) {
      cfg.getServices().add(parseService(svc));
    }
    for (Map<String, Object> c : asMapList(root.get("consumers"))) {
      cfg.getConsumers().add(parseConsumer(c));
    }
    for (Map<String, Object> p : asMapList(root.get("plugins"))) {
      cfg.getPlugins().add(parsePlugin(p, SCOPE_GLOBAL, null));
    }
    return cfg;
  }

  private KongService parseService(Map<String, Object> map) {
    KongService svc = new KongService();
    svc.setName(asString(map.get("name")));
    svc.setHost(asString(map.get("host")));
    svc.setPort(asInt(map.get("port")));
    svc.setProtocol(asString(map.get("protocol")));
    svc.setPath(asString(map.get("path")));

    for (Map<String, Object> r : asMapList(map.get("routes"))) {
      svc.getRoutes().add(parseRoute(r, svc.getName()));
    }
    for (Map<String, Object> p : asMapList(map.get("plugins"))) {
      svc.getPlugins().add(parsePlugin(p, SCOPE_SERVICE, svc.getName()));
    }
    return svc;
  }

  private KongRoute parseRoute(Map<String, Object> map, String serviceName) {
    KongRoute r = new KongRoute();
    r.setName(asString(map.get("name")));
    r.setPaths(asStringList(map.get("paths")));
    r.setMethods(asStringList(map.get("methods")));
    r.setHosts(asStringList(map.get("hosts")));
    Object sp = map.get("strip_path");
    if (sp instanceof Boolean b) {
      r.setStripPath(b);
    }
    for (Map<String, Object> p : asMapList(map.get("plugins"))) {
      r.getPlugins().add(parsePlugin(p, SCOPE_ROUTE, r.getName()));
    }
    return r;
  }

  private KongConsumer parseConsumer(Map<String, Object> map) {
    KongConsumer c = new KongConsumer();
    c.setUsername(asString(map.get("username")));
    c.setCustomId(asString(map.get("custom_id")));

    for (Map<String, Object> cred : asMapList(map.get("jwt_secrets"))) {
      c.getJwtSecrets().add(parseCredential(cred, "jwt"));
    }
    for (Map<String, Object> cred : asMapList(map.get("keyauth_credentials"))) {
      c.getKeyauthCredentials().add(parseCredential(cred, "key-auth"));
    }
    for (Map<String, Object> cred : asMapList(map.get("basicauth_credentials"))) {
      c.getBasicauthCredentials().add(parseCredential(cred, "basic-auth"));
    }
    for (Map<String, Object> p : asMapList(map.get("plugins"))) {
      c.getPlugins().add(parsePlugin(p, SCOPE_CONSUMER, c.getUsername()));
    }
    return c;
  }

  private KongCredential parseCredential(Map<String, Object> map, String type) {
    KongCredential cred = new KongCredential();
    cred.setType(type);
    cred.setKey(asString(map.get("key")));
    cred.setSecret(asString(map.get("secret")));
    cred.setAlgorithm(asString(map.get("algorithm")));
    cred.setUsername(asString(map.get("username")));
    cred.setPassword(asString(map.get("password")));
    return cred;
  }

  @SuppressWarnings("unchecked")
  private KongPlugin parsePlugin(Map<String, Object> map, String scope, String scopeRef) {
    KongPlugin p = new KongPlugin();
    p.setName(asString(map.get("name")));
    Object enabled = map.get("enabled");
    if (enabled instanceof Boolean b) {
      p.setEnabled(b);
    }
    Object cfg = map.get("config");
    if (cfg instanceof Map<?, ?> m) {
      p.setConfig(new LinkedHashMap<>((Map<String, Object>) m));
    }
    p.setScope(scope);
    p.setScopeRef(scopeRef);
    return p;
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static Integer asInt(Object o) {
    if (o instanceof Number n) {
      return n.intValue();
    }
    if (o instanceof String s && !s.isBlank()) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asMapList(Object o) {
    if (!(o instanceof List<?> l)) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> out = new ArrayList<>(l.size());
    for (Object e : l) {
      if (e instanceof Map<?, ?> m) {
        out.add((Map<String, Object>) m);
      }
    }
    return out;
  }

  private static List<String> asStringList(Object o) {
    if (!(o instanceof List<?> l)) {
      return new ArrayList<>();
    }
    List<String> out = new ArrayList<>(l.size());
    for (Object e : l) {
      if (e != null) {
        out.add(e.toString());
      }
    }
    return out;
  }
}
