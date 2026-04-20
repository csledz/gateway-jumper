// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.controller.steps;

import io.telekom.gateway.controller.snapshot.ConfigSnapshot;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Scenario-scoped (effectively singleton-scoped per JVM run) scratch space for cucumber steps. */
@Component
public class TestWorld {

  private final List<ConfigSnapshot> pushedSnapshots = new CopyOnWriteArrayList<>();

  public void recordPush(ConfigSnapshot snap) {
    pushedSnapshots.add(snap);
  }

  public List<ConfigSnapshot> getPushedSnapshots() {
    return pushedSnapshots;
  }

  public void reset() {
    pushedSnapshots.clear();
  }
}
