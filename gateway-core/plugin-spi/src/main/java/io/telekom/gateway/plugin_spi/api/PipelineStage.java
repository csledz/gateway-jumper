/*
 * SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.telekom.gateway.plugin_spi.api;

/**
 * Pipeline stages at which a {@link GatewayPlugin} may be invoked.
 *
 * <p>This enum is intentionally duplicated in the sibling {@code proxy} module so the two modules
 * stay independently releasable. <b>This file is the canonical definition</b>; the sibling copy
 * must track it.
 *
 * <p>Stages fire in the order they are declared here. Within a stage, plugins run ordered by {@link
 * GatewayPlugin#order()} ascending.
 */
public enum PipelineStage {
  /** Immediately after the request is accepted, before routing decisions. */
  PRE_ROUTING,

  /** After route selection, before the upstream call is made. */
  PRE_UPSTREAM,

  /** After the upstream has responded, before the response is sent downstream. */
  POST_UPSTREAM,

  /** Terminal stage, after the response has been written (observability, cleanup). */
  POST_RESPONSE
}
