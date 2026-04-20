// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.policy_engine.registry;

import io.telekom.gateway.policy_engine.api.Policy;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory policy registry. Policies are looked up by their {@code name}; the registry is seeded
 * from a snapshot (e.g. the controller's resource cache) and can be updated at runtime.
 */
@Slf4j
@Component
public class PolicyRegistry {

  private final ConcurrentMap<String, Policy> policies = new ConcurrentHashMap<>();

  public void register(Policy policy) {
    policies.put(policy.name(), policy);
    log.debug("registered policy name={} lang={}", policy.name(), policy.language());
  }

  public void unregister(String name) {
    policies.remove(name);
  }

  /** Replace the entire registry atomically with {@code snapshot}. */
  public void seed(Map<String, Policy> snapshot) {
    policies.clear();
    if (snapshot != null) {
      snapshot.values().forEach(this::register);
    }
  }

  public Optional<Policy> find(String name) {
    return Optional.ofNullable(policies.get(name));
  }

  public Collection<Policy> all() {
    return policies.values();
  }

  public int size() {
    return policies.size();
  }
}
