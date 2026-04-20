# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Read-only routes endpoint

  Background:
    Given the admin API is seeded with a route "echo-route" in zone "zone-a"

  Scenario: List all routes
    When an authenticated client calls GET "/admin/routes"
    Then the response status is 200
    And the response body contains "echo-route"

  Scenario: Fetch a single route by id
    When an authenticated client calls GET "/admin/routes/echo-route"
    Then the response status is 200
    And the response body contains "echo-route"

  Scenario: Fetch a missing route returns 404
    When an authenticated client calls GET "/admin/routes/no-such-route"
    Then the response status is 404
