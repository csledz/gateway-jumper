# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Read-only zones endpoint

  Background:
    Given the admin API is seeded with a zone "zone-a" with 1 route

  Scenario: List all zones
    When an authenticated client calls GET "/admin/zones"
    Then the response status is 200
    And the response body contains "zone-a"

  Scenario: Fetch zone health snapshot
    When an authenticated client calls GET "/admin/zones/zone-a/health"
    Then the response status is 200
    And the response body contains "zone-a"
    And the response body contains "status"
