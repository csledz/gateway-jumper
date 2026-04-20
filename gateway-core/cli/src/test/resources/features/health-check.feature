# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: gatectl health
  As an operator, I want a quick colored overview of zone health.

  Scenario: All zones healthy
    Given the admin API returns "HEALTHY" for zone "eu-central"
    And the admin API returns "HEALTHY" for zone "us-east"
    When I run "health eu-central us-east --no-color"
    Then the exit code is 0
    And stdout contains "ZONE"
    And stdout contains "eu-central"
    And stdout contains "HEALTHY"

  Scenario: One zone unhealthy yields non-zero exit code
    Given the admin API returns "HEALTHY" for zone "eu-central"
    And the admin API returns "UNHEALTHY" for zone "us-east"
    When I run "health eu-central us-east --no-color"
    Then the exit code is 1
    And stdout contains "UNHEALTHY"
