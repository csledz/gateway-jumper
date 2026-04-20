# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Structured JSON logging with MDC propagation

  Scenario: MDC keys survive across reactor operator boundaries
    Given MDC entry "traceId" is set to "abc-123"
    And MDC entry "route" is set to "echo-route"
    When a mono is subscribed on a different scheduler
    Then the MDC entries are still visible inside the mono
    And the reactor context written by the helper exposes "traceId" as "abc-123"

  Scenario: Logstash encoder emits JSON with MDC keys
    Given a log event is emitted with MDC entry "traceId" set to "log-trace"
    Then the encoded log line is valid JSON
    And the encoded log line contains field "traceId" with value "log-trace"
    And the encoded log line contains field "message"
