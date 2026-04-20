// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Kong {@code service} entity with nested routes and plugins. */
@Data
public class KongService {

  private String name;
  private String host;
  private Integer port;
  private String protocol;
  private String path;

  private List<KongRoute> routes = new ArrayList<>();
  private List<KongPlugin> plugins = new ArrayList<>();
}
