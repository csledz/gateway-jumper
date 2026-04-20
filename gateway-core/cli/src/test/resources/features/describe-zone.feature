# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: gatectl describe zone
  As an operator, I want to see the zone CRD spec joined with live health.

  Background:
    Given a kubernetes mock with a zone "eu-central" in namespace "gateway-system"
    And the admin API returns "HEALTHY" for zone "eu-central"

  Scenario: Describe shows spec and health status
    When I run "describe-zone eu-central -n gateway-system"
    Then the exit code is 0
    And stdout contains "Name: eu-central"
    And stdout contains "Health:"
    And stdout contains "Status: HEALTHY"

  Scenario: Describe falls back gracefully when admin API is unreachable
    Given the admin API is unreachable
    When I run "describe-zone eu-central -n gateway-system"
    Then the exit code is 0
    And stdout contains "Status: UNKNOWN"
