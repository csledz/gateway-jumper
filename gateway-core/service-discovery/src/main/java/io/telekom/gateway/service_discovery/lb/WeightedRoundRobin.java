// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.lb;

import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stateless picker that implements two strategies:
 *
 * <ul>
 *   <li>Weighted random (port of jumper's {@code LoadBalancingUtil#calculateUpstream}): picks an
 *       endpoint with probability proportional to its weight.
 *   <li>Simple round-robin fallback: useful when weights are uniform or absent.
 * </ul>
 *
 * Unhealthy endpoints (and zero-weight ones) are filtered out before selection.
 */
public final class WeightedRoundRobin {

  /**
   * Monotonic counter used by {@link #roundRobin(List)}; wraps naturally at {@code
   * Integer.MAX_VALUE}.
   */
  private final AtomicInteger cursor = new AtomicInteger();

  /** Pick one endpoint using weighted random. Returns {@code null} if none are eligible. */
  public ServiceEndpoint pick(List<ServiceEndpoint> endpoints) {
    if (endpoints == null || endpoints.isEmpty()) {
      return null;
    }
    // Sum total of weights (healthy-only).
    double total = 0;
    for (ServiceEndpoint e : endpoints) {
      if (e.healthy()) {
        total += e.weight();
      }
    }
    if (total <= 0) {
      return null;
    }
    // Random number in [0, total).
    double random = ThreadLocalRandom.current().nextDouble() * total;
    // Seek cursor to find which bucket the random falls into.
    double c = 0;
    for (ServiceEndpoint e : endpoints) {
      if (!e.healthy()) {
        continue;
      }
      c += e.weight();
      if (c > random) {
        return e;
      }
    }
    // Fallback — can happen under floating-point rounding.
    return endpoints.get(endpoints.size() - 1);
  }

  /** Simple stateful round-robin over healthy endpoints. */
  public ServiceEndpoint roundRobin(List<ServiceEndpoint> endpoints) {
    if (endpoints == null || endpoints.isEmpty()) {
      return null;
    }
    List<ServiceEndpoint> healthy = endpoints.stream().filter(ServiceEndpoint::healthy).toList();
    if (healthy.isEmpty()) {
      return null;
    }
    int idx = Math.floorMod(cursor.getAndIncrement(), healthy.size());
    return healthy.get(idx);
  }
}
