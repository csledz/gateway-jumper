// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import lombok.Data;

/**
 * Flat representation of a Kong credential of any supported type (jwt, key-auth, basic-auth). The
 * {@link #type} field distinguishes the variant.
 */
@Data
public class KongCredential {

  /** One of: {@code jwt}, {@code key-auth}, {@code basic-auth}. */
  private String type;

  private String key;
  private String secret;
  private String algorithm;
  private String username;
  private String password;

  /** JWT issuer claim (optional, reserved for JWT credentials). */
  private String issuer;

  /** JWKS URI for JWT issuer (optional, reserved for JWT credentials). */
  private String jwksUri;
}
