# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Cross-zone mesh hop
  A request that enters zone A and is routed to an upstream living in zone B must
  traverse the peer mesh hop, carrying a mesh-JWT whose claims identify the origin.

  Background:
    Given the three-zone mesh is up
    And upstream in zone B is primed to echo mesh headers

  Scenario: Request from zone A to an upstream in zone B
    When a GET request is sent to zone A path "/api/orders/42" targeting peer zone B
    Then the response status is 200
    And the response header "X-Failover-Chosen-Zone" equals "B"
    And the response header "X-Forwarded-By" equals "zone-B"
    And the response header "X-Origin-Zone" equals "A"
    And the response header "X-Origin-Stargate" equals "stargate-A.local"
    And the response header "X-Mesh-Audience" equals "zone-B"
    And upstream in zone B saw 1 requests
