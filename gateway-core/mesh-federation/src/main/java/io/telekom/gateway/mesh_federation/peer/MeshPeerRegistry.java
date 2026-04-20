// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.peer;

import io.telekom.gateway.mesh_federation.model.MeshPeer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only-per-lookup view of {@link MeshPeer} entries declared by the operator. The reconciling
 * controller (external to this module) calls {@link #replaceAll(Map)} when the CRD snapshot
 * changes; callers see a consistent map snapshot.
 */
public class MeshPeerRegistry {

  private volatile Map<String, MeshPeer> byPeerZone = Map.of();
  private final ConcurrentHashMap<String, Object> lock = new ConcurrentHashMap<>();

  public Optional<MeshPeer> resolve(String peerZone) {
    return Optional.ofNullable(byPeerZone.get(peerZone));
  }

  public Collection<MeshPeer> all() {
    return Collections.unmodifiableCollection(byPeerZone.values());
  }

  /** Replace the whole registry atomically; safe to call from CRD watcher threads. */
  public void replaceAll(Map<String, MeshPeer> next) {
    synchronized (lock) {
      byPeerZone = Map.copyOf(next);
    }
  }
}
