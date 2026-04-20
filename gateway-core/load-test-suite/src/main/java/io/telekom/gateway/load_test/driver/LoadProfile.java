// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.load_test.driver;

import java.util.Map;

/**
 * Immutable description of a load run the {@link LoadDriver} should emit.
 *
 * @param targetRps steady-state requests per second
 * @param durationSeconds total duration of the run (after ramp)
 * @param concurrency max number of in-flight requests; acts as a safety cap against runaway queuing
 * @param rampSeconds ramp-up from 0 to {@code targetRps} over this many seconds (0 = step load)
 * @param headers extra HTTP headers to attach to every request
 * @param bodyBytes number of bytes in the request body (0 = no body, which is the default)
 */
public record LoadProfile(
    int targetRps,
    int durationSeconds,
    int concurrency,
    int rampSeconds,
    Map<String, String> headers,
    int bodyBytes) {

  /** Minimal GET profile: no body, no custom headers. */
  public static LoadProfile steady(int rps, int durationSeconds, int concurrency) {
    return new LoadProfile(rps, durationSeconds, concurrency, 0, Map.of(), 0);
  }
}
