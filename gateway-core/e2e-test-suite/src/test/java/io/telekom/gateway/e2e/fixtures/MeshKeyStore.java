// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.fixtures;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory RSA keypair registry used by the embedded zone proxies.
 *
 * <p>Each zone owns one keypair. Peer zones trust each other's public keys by looking them up by
 * zone name. This reimplements - in a minimal way - what the real {@code mesh-jwt} module will
 * later ship as a JWKS-backed resolver.
 */
public final class MeshKeyStore {

  private static final Map<String, KeyPair> ZONE_KEYS = new ConcurrentHashMap<>();

  private MeshKeyStore() {}

  public static synchronized KeyPair keyFor(String zone) {
    return ZONE_KEYS.computeIfAbsent(zone, z -> generate());
  }

  public static String issue(String zone, String audience, String originStargate, long ttlMs) {
    KeyPair kp = keyFor(zone);
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .setIssuer("zone-" + zone)
        .setAudience(audience)
        .setSubject("mesh-" + zone)
        .setIssuedAt(new Date(now))
        .setExpiration(new Date(now + ttlMs))
        .claim("originZone", zone)
        .claim("originStargate", originStargate)
        .signWith(kp.getPrivate(), SignatureAlgorithm.RS256)
        .compact();
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA not available", e);
    }
  }
}
