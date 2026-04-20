# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: gatectl get routes
  As an operator, I want to list gateway routes across output formats.

  Background:
    Given a kubernetes mock with 2 routes in namespace "gateway-system"

  Scenario: Default table output
    When I run "get-routes -n gateway-system"
    Then the exit code is 0
    And stdout contains "NAME"
    And stdout contains "HOST"
    And stdout contains "orders-route"
    And stdout contains "payments-route"

  Scenario: JSON output
    When I run "get-routes -n gateway-system -o json"
    Then the exit code is 0
    And stdout is valid JSON
    And stdout JSON has 2 entries

  Scenario: YAML output
    When I run "get-routes -n gateway-system -o yaml"
    Then the exit code is 0
    And stdout contains "NAME: orders-route"

  Scenario: Empty namespace yields just headers
    Given a kubernetes mock with 0 routes in namespace "empty-ns"
    When I run "get-routes -n empty-ns"
    Then the exit code is 0
    And stdout contains "NAME"
