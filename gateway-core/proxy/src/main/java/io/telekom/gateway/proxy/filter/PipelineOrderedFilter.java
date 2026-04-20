// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.proxy.filter;

/** Base type for gateway filters that declare their pipeline stage. */
public abstract class PipelineOrderedFilter {

  /** The stage this filter belongs to. */
  public abstract FilterPipelineStage stage();
}
