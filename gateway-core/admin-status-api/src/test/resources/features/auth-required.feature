# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Admin endpoints require authentication

  Scenario: Unauthenticated GET /admin/routes returns 401
    When an unauthenticated client calls GET "/admin/routes"
    Then the response status is 401

  Scenario: Unauthenticated GET /admin/zones returns 401
    When an unauthenticated client calls GET "/admin/zones"
    Then the response status is 401

  Scenario: Unauthenticated GET /admin/snapshot returns 401
    When an unauthenticated client calls GET "/admin/snapshot"
    Then the response status is 401
