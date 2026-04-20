// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.auth_outbound.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads an RSA signing key and its kid from filesystem paths. Production deployments mount the
 * material from a Secret; test fixtures generate an ephemeral keypair at runtime and point the
 * loader at the temp files.
 */
public class RsaKeyLoader {

  private final PrivateKey privateKey;
  private final String kid;

  public RsaKeyLoader(Path keyPath, Path kidPath) throws IOException {
    byte[] keyBytes = Files.readAllBytes(keyPath);
    this.privateKey = parsePkcs8(keyBytes);
    this.kid = Files.readString(kidPath).trim();
  }

  public PrivateKey privateKey() {
    return privateKey;
  }

  public String kid() {
    return kid;
  }

  private static PrivateKey parsePkcs8(byte[] bytes) {
    byte[] decoded = maybeBase64Decode(bytes);
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalArgumentException("failed to parse PKCS#8 RSA key", e);
    }
  }

  private static byte[] maybeBase64Decode(byte[] bytes) {
    String s = new String(bytes).trim();
    if (s.startsWith("-----BEGIN")) {
      // strip PEM header/footer, collapse whitespace
      String body = s.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
      return Base64.getDecoder().decode(body);
    }
    return bytes;
  }
}
