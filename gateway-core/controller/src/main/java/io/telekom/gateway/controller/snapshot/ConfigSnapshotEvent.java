// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.snapshot;

import org.springframework.context.ApplicationEvent;

/** Published by reconcilers whenever a watched CRD changes; triggers snapshot rebuild + push. */
public class ConfigSnapshotEvent extends ApplicationEvent {

  private final String zone;
  private final String cause;

  public ConfigSnapshotEvent(Object source, String zone, String cause) {
    super(source);
    this.zone = zone;
    this.cause = cause;
  }

  public String getZone() {
    return zone;
  }

  public String getCause() {
    return cause;
  }
}
