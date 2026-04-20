# SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Per-route bulkhead
  The gateway-core bulkhead caps concurrent in-flight calls and replies 429 when full.

  Scenario: Bulkhead rejects overflow traffic with 429
    Given a bulkhead policy with max concurrent 2
    And the upstream for "/slow" delays responses by 500 ms with status 200
    When I send 6 concurrent GET requests to "/bh/slow"
    Then at most 2 responses have status 200
    And at least 4 responses have status 429
