// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Kong {@code consumer} with associated credentials. */
@Data
public class KongConsumer {

  private String username;
  private String customId;

  private List<KongCredential> jwtSecrets = new ArrayList<>();
  private List<KongCredential> keyauthCredentials = new ArrayList<>();
  private List<KongCredential> basicauthCredentials = new ArrayList<>();

  private List<KongPlugin> plugins = new ArrayList<>();
}
