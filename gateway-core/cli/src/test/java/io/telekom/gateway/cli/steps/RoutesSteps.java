// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.steps;

import io.cucumber.java.en.Given;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.util.ArrayList;
import java.util.List;

/** Fixture steps that pre-populate the fabric8 mock with Route CRDs. */
public class RoutesSteps {

  @Given("a kubernetes mock with {int} routes in namespace {string}")
  public void aKubernetesMockWithRoutes(int count, String namespace) {
    World w = World.current();
    w.ensureKubeMock();
    List<GenericKubernetesResource> routes = new ArrayList<>();
    if (count >= 1) {
      routes.add(w.buildRoute(namespace, "orders-route"));
    }
    if (count >= 2) {
      routes.add(w.buildRoute(namespace, "payments-route"));
    }
    for (int i = 3; i <= count; i++) {
      routes.add(w.buildRoute(namespace, "route-" + i));
    }
    w.stubRouteList(namespace, routes);
  }
}
