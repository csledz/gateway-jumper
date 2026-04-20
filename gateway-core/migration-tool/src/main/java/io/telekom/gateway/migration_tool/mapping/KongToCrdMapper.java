// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.mapping;

import io.telekom.gateway.migration_tool.model.CrdResource;
import io.telekom.gateway.migration_tool.model.DeckConfig;
import io.telekom.gateway.migration_tool.model.KongConsumer;
import io.telekom.gateway.migration_tool.model.KongCredential;
import io.telekom.gateway.migration_tool.model.KongPlugin;
import io.telekom.gateway.migration_tool.model.KongRoute;
import io.telekom.gateway.migration_tool.model.KongService;
import io.telekom.gateway.migration_tool.model.MigrationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates a {@link DeckConfig} tree into gateway-core CRDs.
 *
 * <p>Mapping rules:
 *
 * <ul>
 *   <li>Kong {@code service + route} → one {@code GatewayRoute} per route, referencing the service
 *       as an upstream target. Route-scoped and service-scoped consumer attachments are recorded as
 *       cross-refs.
 *   <li>Kong {@code consumer} → {@code GatewayConsumer}.
 *   <li>Kong credentials ({@code jwt}, {@code key-auth}, {@code basic-auth}) → {@code
 *       GatewayCredential} CRDs linked to their consumer.
 *   <li>Kong plugins {@code rate-limiting}, {@code cors}, {@code request-validator} → {@code
 *       GatewayPolicy} CRDs. All other plugins are recorded in {@link UnmigratedReport}.
 * </ul>
 */
@Slf4j
public final class KongToCrdMapper {

  public static final String API_VERSION = "gateway.telekom.io/v1alpha1";

  public MigrationResult map(DeckConfig cfg) {
    MigrationResult result = new MigrationResult();
    if (cfg == null) {
      return result;
    }

    for (KongService svc : cfg.getServices()) {
      mapService(svc, result);
    }
    for (KongConsumer c : cfg.getConsumers()) {
      mapConsumer(c, result);
    }
    for (KongPlugin p : cfg.getPlugins()) {
      mapPlugin(p, result);
    }
    return result;
  }

  // --- services / routes --------------------------------------------------

  private void mapService(KongService svc, MigrationResult out) {
    for (KongPlugin p : svc.getPlugins()) {
      mapPlugin(p, out);
    }
    if (svc.getRoutes().isEmpty()) {
      log.debug("service {} has no routes; nothing to emit", svc.getName());
      return;
    }
    for (KongRoute route : svc.getRoutes()) {
      out.getResources().add(buildGatewayRoute(svc, route));
      for (KongPlugin p : route.getPlugins()) {
        mapPlugin(p, out);
      }
    }
  }

  private CrdResource buildGatewayRoute(KongService svc, KongRoute route) {
    String name = route.getName() != null ? route.getName() : svc.getName() + "-route";
    CrdResource r = CrdResource.of(API_VERSION, "GatewayRoute", name);

    Map<String, Object> upstream = new LinkedHashMap<>();
    upstream.put("host", svc.getHost());
    if (svc.getPort() != null) {
      upstream.put("port", svc.getPort());
    }
    if (svc.getProtocol() != null) {
      upstream.put("protocol", svc.getProtocol());
    }
    if (svc.getPath() != null) {
      upstream.put("path", svc.getPath());
    }

    Map<String, Object> match = new LinkedHashMap<>();
    if (!route.getPaths().isEmpty()) {
      match.put("paths", new ArrayList<>(route.getPaths()));
    }
    if (!route.getMethods().isEmpty()) {
      match.put("methods", new ArrayList<>(route.getMethods()));
    }
    if (!route.getHosts().isEmpty()) {
      match.put("hosts", new ArrayList<>(route.getHosts()));
    }

    r.getSpec().put("upstream", upstream);
    r.getSpec().put("match", match);
    if (route.getStripPath() != null) {
      r.getSpec().put("stripPath", route.getStripPath());
    }

    // cross-ref: which policies and which consumers apply at this route
    List<String> policyRefs = new ArrayList<>();
    for (KongPlugin p : svc.getPlugins()) {
      if (isPolicyPlugin(p)) {
        policyRefs.add(policyName(p));
      }
    }
    for (KongPlugin p : route.getPlugins()) {
      if (isPolicyPlugin(p)) {
        policyRefs.add(policyName(p));
      }
    }
    if (!policyRefs.isEmpty()) {
      r.getSpec().put("policyRefs", policyRefs);
    }
    return r;
  }

  // --- consumers / credentials --------------------------------------------

  private void mapConsumer(KongConsumer c, MigrationResult out) {
    String name = c.getUsername() != null ? c.getUsername() : c.getCustomId();
    if (name == null) {
      log.warn("skipping consumer without username or custom_id");
      return;
    }

    CrdResource consumer = CrdResource.of(API_VERSION, "GatewayConsumer", name);
    if (c.getUsername() != null) {
      consumer.getSpec().put("username", c.getUsername());
    }
    if (c.getCustomId() != null) {
      consumer.getSpec().put("customId", c.getCustomId());
    }
    List<String> credentialRefs = new ArrayList<>();
    emitCredentials(name, "jwt", c.getJwtSecrets(), out, credentialRefs);
    emitCredentials(name, "keyauth", c.getKeyauthCredentials(), out, credentialRefs);
    emitCredentials(name, "basicauth", c.getBasicauthCredentials(), out, credentialRefs);
    if (!credentialRefs.isEmpty()) {
      consumer.getSpec().put("credentialRefs", credentialRefs);
    }
    out.getResources().add(consumer);

    for (KongPlugin p : c.getPlugins()) {
      mapPlugin(p, out);
    }
  }

  private void emitCredentials(
      String consumerName,
      String typeSlug,
      List<KongCredential> creds,
      MigrationResult out,
      List<String> refs) {
    for (int i = 0; i < creds.size(); i++) {
      String credName = credentialName(consumerName, typeSlug, i);
      out.getResources().add(buildCredential(credName, consumerName, creds.get(i)));
      refs.add(credName);
    }
  }

  private CrdResource buildCredential(String name, String consumerName, KongCredential cred) {
    CrdResource r = CrdResource.of(API_VERSION, "GatewayCredential", name);
    r.getSpec().put("consumerRef", consumerName);
    r.getSpec().put("type", cred.getType());

    Map<String, Object> data = new LinkedHashMap<>();
    switch (cred.getType()) {
      case "jwt" -> {
        if (cred.getKey() != null) data.put("key", cred.getKey());
        if (cred.getSecret() != null) data.put("secret", cred.getSecret());
        if (cred.getAlgorithm() != null) data.put("algorithm", cred.getAlgorithm());
      }
      case "key-auth" -> {
        if (cred.getKey() != null) data.put("key", cred.getKey());
      }
      case "basic-auth" -> {
        if (cred.getUsername() != null) data.put("username", cred.getUsername());
        if (cred.getPassword() != null) data.put("password", cred.getPassword());
      }
      default -> {
        // unreachable given reader
      }
    }
    r.getSpec().put("data", data);
    return r;
  }

  // --- plugins ------------------------------------------------------------

  private void mapPlugin(KongPlugin p, MigrationResult out) {
    if (p == null || p.getName() == null) {
      return;
    }
    switch (p.getName()) {
      case "rate-limiting" -> out.getResources().add(buildRateLimitingPolicy(p));
      case "cors" -> out.getResources().add(buildCorsPolicy(p));
      case "request-validator" -> out.getResources().add(buildRequestValidatorPolicy(p));
      default -> out.getUnmigrated().add(UnmigratedReport.describe(p));
    }
  }

  static boolean isPolicyPlugin(KongPlugin p) {
    if (p == null || p.getName() == null) return false;
    return switch (p.getName()) {
      case "rate-limiting", "cors", "request-validator" -> true;
      default -> false;
    };
  }

  private CrdResource buildRateLimitingPolicy(KongPlugin p) {
    CrdResource r = CrdResource.of(API_VERSION, "GatewayPolicy", policyName(p));
    r.getSpec().put("type", "RateLimiting");
    Map<String, Object> cfg = new LinkedHashMap<>();
    Object perSecond = p.getConfig().get("second");
    Object perMinute = p.getConfig().get("minute");
    Object perHour = p.getConfig().get("hour");
    if (perSecond != null) cfg.put("perSecond", perSecond);
    if (perMinute != null) cfg.put("perMinute", perMinute);
    if (perHour != null) cfg.put("perHour", perHour);
    if (p.getConfig().get("policy") != null) cfg.put("policy", p.getConfig().get("policy"));
    r.getSpec().put("config", cfg);
    attachScope(r, p);
    return r;
  }

  private CrdResource buildCorsPolicy(KongPlugin p) {
    CrdResource r = CrdResource.of(API_VERSION, "GatewayPolicy", policyName(p));
    r.getSpec().put("type", "Cors");
    Map<String, Object> cfg = new LinkedHashMap<>();
    copyIfPresent(p.getConfig(), cfg, "origins", "allowedOrigins");
    copyIfPresent(p.getConfig(), cfg, "methods", "allowedMethods");
    copyIfPresent(p.getConfig(), cfg, "headers", "allowedHeaders");
    copyIfPresent(p.getConfig(), cfg, "exposed_headers", "exposedHeaders");
    copyIfPresent(p.getConfig(), cfg, "credentials", "allowCredentials");
    copyIfPresent(p.getConfig(), cfg, "max_age", "maxAge");
    r.getSpec().put("config", cfg);
    attachScope(r, p);
    return r;
  }

  private CrdResource buildRequestValidatorPolicy(KongPlugin p) {
    CrdResource r = CrdResource.of(API_VERSION, "GatewayPolicy", policyName(p));
    r.getSpec().put("type", "RequestValidator");
    Map<String, Object> cfg = new LinkedHashMap<>();
    copyIfPresent(p.getConfig(), cfg, "body_schema", "bodySchema");
    copyIfPresent(p.getConfig(), cfg, "parameter_schema", "parameterSchema");
    copyIfPresent(p.getConfig(), cfg, "version", "version");
    r.getSpec().put("config", cfg);
    attachScope(r, p);
    return r;
  }

  private static void copyIfPresent(
      Map<String, Object> src, Map<String, Object> dst, String srcKey, String dstKey) {
    Object v = src.get(srcKey);
    if (v != null) {
      dst.put(dstKey, v);
    }
  }

  private static void attachScope(CrdResource r, KongPlugin p) {
    if (p.getScope() != null && p.getScopeRef() != null) {
      Map<String, Object> attach = new LinkedHashMap<>();
      attach.put("scope", p.getScope());
      attach.put("ref", p.getScopeRef());
      r.getSpec().put("attachment", attach);
    } else if (p.getScope() != null) {
      Map<String, Object> attach = new LinkedHashMap<>();
      attach.put("scope", p.getScope());
      r.getSpec().put("attachment", attach);
    }
  }

  static String policyName(KongPlugin p) {
    String scope =
        p.getScopeRef() != null
            ? p.getScopeRef()
            : (p.getScope() != null ? p.getScope() : "global");
    return sanitize(scope + "-" + p.getName());
  }

  private static String credentialName(String consumer, String type, int idx) {
    return sanitize(consumer + "-" + type + "-" + idx);
  }

  private static String sanitize(String s) {
    return s.toLowerCase().replaceAll("[^a-z0-9-]", "-");
  }
}
