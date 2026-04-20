// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.migration_tool.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Root model of a Kong declarative (decK) YAML document.
 *
 * <p>Only the fields used by the migration tool are modelled; unknown fields are preserved raw in
 * sub-structures where we care about them (plugin {@code config}).
 */
@Data
public class DeckConfig {

  private String formatVersion;
  private List<KongService> services = new ArrayList<>();
  private List<KongConsumer> consumers = new ArrayList<>();

  /** Plugins defined at the top level (not attached to a specific service/route/consumer). */
  private List<KongPlugin> plugins = new ArrayList<>();
}
