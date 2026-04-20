# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: CRD change triggers a snapshot push

  The controller must react to any watched CRD changing by emitting a snapshot event
  and pushing a refreshed snapshot to every registered data-plane URL.

  Scenario: Adding a GatewayRoute publishes a snapshot event
    Given the controller cache is empty
    And a data-plane is registered
    When a GatewayRoute named "echo-route" is created in zone "zone-a"
    Then a snapshot push for zone "zone-a" is recorded
    And the pushed snapshot contains 1 route

  Scenario: Deleting a GatewayConsumer publishes a snapshot event
    Given the controller cache is empty
    And a data-plane is registered
    And a GatewayConsumer named "client-1" exists in zone "zone-a"
    When the GatewayConsumer "client-1" is removed
    Then a snapshot push for zone "zone-a" is recorded
    And the pushed snapshot contains 0 consumers
