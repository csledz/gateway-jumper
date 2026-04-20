# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Push retry on transient data-plane failure

  When a data-plane pod returns a transient 5xx or a connection error, the
  controller must retry with backoff and ultimately succeed if the data-plane
  recovers within the retry budget.

  Scenario: Transient 503 is retried and succeeds
    Given the controller cache is empty
    And a flaky data-plane that fails the first 2 calls with status 503 is registered
    And a GatewayZone "zone-a" exists
    And a GatewayRoute named "echo-route" is created in zone "zone-a"
    When a snapshot for zone "zone-a" is pushed
    Then the flaky data-plane received at least 3 calls
    And the last push was successful

  Scenario: Permanent 400 is not retried
    Given the controller cache is empty
    And a data-plane that always returns 400 is registered
    And a GatewayZone "zone-a" exists
    When a snapshot for zone "zone-a" is pushed
    Then the failing data-plane received exactly 1 call
