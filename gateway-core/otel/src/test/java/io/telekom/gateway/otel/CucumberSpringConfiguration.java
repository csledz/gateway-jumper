// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Binds Cucumber step definitions to the Spring Boot test application context defined by {@link
 * OtelTestApplication}. One context is shared across all scenarios — the in-memory exporter is
 * reset between scenarios via {@link OtelPipelineSteps#beforeEach()}.
 */
@CucumberContextConfiguration
@SpringBootTest(
    classes = OtelTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.application.name=gateway-core-otel-test",
      "gateway.otel.enabled=true",
      // Autoconfigure is disabled — the test rig provides its own SDK so we can attach the
      // in-memory verification exporter and keep the suite hermetic.
      "gateway.otel.sdk.autoconfigure=false",
      "gateway.otel.logs.enabled=true",
      "gateway.zone.name=test-zone",
      "gateway.realm.name=test-realm",
      "management.otlp.metrics.export.enabled=false",
      "management.prometheus.metrics.export.enabled=true",
    })
public class CucumberSpringConfiguration {}
