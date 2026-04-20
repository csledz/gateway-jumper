# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: gateway-core OpenTelemetry pipeline

  The otel module wires traces, metrics, and logs in one consistent stack.
  These scenarios run against an in-memory verification exporter so the suite
  is hermetic — no Docker collector is required for the CI happy path.

  Background:
    Given the gateway-core otel module is wired

  Scenario: Traces are exported with gateway-core resource attributes
    When a client calls the demo handler
    Then a span with service.name "gateway-core-otel-test" is exported

  Scenario: Metrics are exported through the Micrometer-to-OTLP bridge
    When a client calls the demo handler
    Then the demo counter metric is exported

  Scenario: Logs are bridged from Logback to the OTel logger
    When a client calls the demo handler
    Then a log record is bridged through the OTel logger

  Scenario: Inbound B3 multi-header trace context is honoured (jumper interop)
    When a client calls the demo handler with a B3 multi-header trace context
    Then the exported span carries the inbound B3 trace id
