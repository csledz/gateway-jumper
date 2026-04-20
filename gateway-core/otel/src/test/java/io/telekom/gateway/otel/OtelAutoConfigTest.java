// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/** Cucumber entry point — the Maven surefire run picks this up as a JUnit suite. */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/otel-pipeline.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.telekom.gateway.otel")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, summary, html:target/cucumber-report.html")
public class OtelAutoConfigTest {}
