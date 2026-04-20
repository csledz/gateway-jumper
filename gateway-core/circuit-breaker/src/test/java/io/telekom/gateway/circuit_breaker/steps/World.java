// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.steps;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

/** Per-scenario scratch state shared between step classes. */
@Component
@Getter
@Setter
public class World {
  private final List<Integer> responseStatuses = new CopyOnWriteArrayList<>();
  private final List<Long> responseDurationsMillis = new ArrayList<>();
  private String lastRouteId;

  public void reset() {
    responseStatuses.clear();
    responseDurationsMillis.clear();
    lastRouteId = null;
  }
}
