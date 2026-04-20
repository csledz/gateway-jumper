// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/** Kong {@code plugin} entity as found anywhere in the decK YAML. */
@Data
public class KongPlugin {

  private String name;
  private Boolean enabled;
  private Map<String, Object> config = new LinkedHashMap<>();

  /**
   * Scope in which this plugin was encountered: {@code global}, {@code service}, {@code route}, or
   * {@code consumer}. Filled in by the reader.
   */
  private String scope;

  /** Name of the enclosing entity (service name / route name / consumer username). */
  private String scopeRef;
}
