// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.e2e.steps;

import io.telekom.gateway.e2e.MeshTopology;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Per-scenario scratchpad shared between step classes via Cucumber-Spring. Holds the topology
 * handle, the last HTTP response, and any bookkeeping steps need to hand off to each other.
 */
public class World {

  public final MeshTopology topology = MeshTopology.getInstance();
  public final Map<String, Object> bag = new ConcurrentHashMap<>();
  public volatile ResponseEntity<String> lastResponse;

  public HttpHeaders lastHeaders() {
    return lastResponse == null ? HttpHeaders.EMPTY : lastResponse.getHeaders();
  }

  public void reset() {
    bag.clear();
    lastResponse = null;
  }
}
