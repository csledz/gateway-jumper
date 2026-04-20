# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: RED metrics for gateway requests

  Scenario: Hitting /actuator/health increments request counter and timer
    Given the current value of gateway.requests for route "actuator" is captured
    When a client calls "GET" "/actuator/health"
    Then the response status is 200
    And the gateway.requests counter has increased
    And the gateway.request.duration timer has at least one sample
    And the metrics carry tags for route, method, status and zone

  Scenario: Prometheus endpoint exposes gateway metrics
    When a client calls "GET" "/actuator/prometheus"
    Then the response status is 200
    And the response body contains "gateway_requests_total"
    And the response body contains "gateway_request_duration_seconds"
