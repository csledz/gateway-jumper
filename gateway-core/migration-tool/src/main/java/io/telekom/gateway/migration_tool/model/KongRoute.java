// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Kong {@code route} entity, always nested under a service in decK YAML. */
@Data
public class KongRoute {

  private String name;
  private List<String> paths = new ArrayList<>();
  private List<String> methods = new ArrayList<>();
  private List<String> hosts = new ArrayList<>();
  private Boolean stripPath;

  private List<KongPlugin> plugins = new ArrayList<>();
}
