// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.proxy.filter;

/** Ordered stages of the gateway filter pipeline. See README for responsibilities. */
public enum FilterPipelineStage {
  REQUEST_VALIDATION(100),
  INBOUND(200),
  RATE_LIMITER(300),
  POLICY(400),
  DISCOVERY(500),
  OUTBOUND(600),
  RESILIENCE(700),
  MESH(800),
  UPSTREAM(900);

  private final int order;

  FilterPipelineStage(int order) {
    this.order = order;
  }

  public int getOrder() {
    return order;
  }
}
