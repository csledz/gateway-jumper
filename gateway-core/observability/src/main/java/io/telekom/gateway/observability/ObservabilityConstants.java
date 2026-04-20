// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability;

/** Shared constants for metric, tag and MDC keys. */
public final class ObservabilityConstants {

  private ObservabilityConstants() {}

  /** Root path prefix used to scope per-URI Netty metrics. Kept generic, no Kong-ism. */
  public static final String GATEWAY_ROOT_PATH_PREFIX = "/gateway";

  public static final String METRIC_REQUESTS = "gateway.requests";
  public static final String METRIC_REQUEST_DURATION = "gateway.request.duration";

  public static final String TAG_ROUTE = "route";
  public static final String TAG_METHOD = "method";
  public static final String TAG_STATUS = "status";
  public static final String TAG_ZONE = "zone";

  public static final String MDC_TRACE_ID = "traceId";
  public static final String MDC_SPAN_ID = "spanId";
  public static final String MDC_ROUTE = "route";
  public static final String MDC_ZONE = "zone";
}
