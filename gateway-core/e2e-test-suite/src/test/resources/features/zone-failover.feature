# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Zone failover via zone-health pub/sub
  When the target zone is announced UNHEALTHY on the Redis zone-health channel, the
  origin proxy must fail over to the next healthy peer and append the skipped zone to
  the X-Failover-Skip-Zone header.

  Background:
    Given the three-zone mesh is up
    And upstream in zone C is primed to echo mesh headers

  Scenario: Zone B is unhealthy, request falls over to zone C
    Given zone B is announced UNHEALTHY
    When a GET request is sent to zone A path "/api/widgets/7" targeting peer zone B
    Then the response status is 200
    And the response header "X-Failover-Chosen-Zone" equals "C"
    And the response header "X-Failover-Skip-Zone" contains "B"
    And the response header "X-Forwarded-By" equals "zone-C"
    And upstream in zone B saw 0 requests
    And upstream in zone C saw 1 requests

  Scenario: Zone becomes healthy again
    Given upstream in zone B is primed to echo mesh headers
    And zone B is announced UNHEALTHY
    And zone B is announced HEALTHY
    When a GET request is sent to zone A path "/api/widgets/8" targeting peer zone B
    Then the response status is 200
    And the response header "X-Failover-Chosen-Zone" equals "B"
