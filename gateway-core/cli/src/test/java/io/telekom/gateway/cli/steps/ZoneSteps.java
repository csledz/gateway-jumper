// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.cli.steps;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import io.cucumber.java.en.Given;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/** Fixture steps that populate Zone CRDs and stub the admin API's health endpoint. */
public class ZoneSteps {

  @Given("a kubernetes mock with a zone {string} in namespace {string}")
  public void aKubernetesMockWithAZone(String name, String namespace) {
    World w = World.current();
    w.ensureKubeMock();
    GenericKubernetesResource zone = w.buildZone(namespace, name);
    w.stubZone(namespace, zone);
  }

  @Given("the admin API returns {string} for zone {string}")
  public void theAdminApiReturnsForZone(String status, String zone) {
    World w = World.current();
    w.ensureAdminMock();
    String body =
        String.format(
            "{\"zone\":\"%s\",\"status\":\"%s\",\"message\":\"ok\",\"checks\":[]}", zone, status);
    w.adminMock
        .when(request().withMethod("GET").withPath("/admin/zones/" + zone + "/health"))
        .respond(response().withStatusCode(200).withBody(body));
  }

  @Given("the admin API is unreachable")
  public void theAdminApiIsUnreachable() {
    World.current().markAdminUnreachable();
  }
}
