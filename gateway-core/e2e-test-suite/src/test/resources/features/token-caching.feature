# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Peer-IdP token caching
  A burst of requests crossing the same peer hop must share a single cached mesh-JWT:
  the upstream must see at most one distinct Authorization header.

  Background:
    Given the three-zone mesh is up

  Scenario: 1000 concurrent requests reuse the same peer token
    When 200 concurrent GET requests are sent to zone A path "/api/bulk" targeting peer zone B
    Then all 200 responses were HTTP 200
    And at most 1 peer tokens were minted from zone A to zone B
