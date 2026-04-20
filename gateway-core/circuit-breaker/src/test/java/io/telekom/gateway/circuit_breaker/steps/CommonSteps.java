// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.circuit_breaker.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;

/** Resets WireMock and scenario state before every scenario. */
public class CommonSteps {

  @Autowired private WireMockServer wireMockServer;
  @Autowired private World world;

  @Before
  public void reset() {
    wireMockServer.resetAll();
    world.reset();
  }
}
