# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Snapshot aggregates multiple resource kinds into one payload

  A single snapshot per zone must contain the zone spec plus every watched
  resource belonging to that zone, and resources in other zones must not leak.

  Scenario: Multi-resource aggregation for one zone
    Given the controller cache is empty
    And a GatewayZone "zone-a" exists
    And a GatewayRoute named "echo-route" is created in zone "zone-a"
    And a GatewayConsumer named "client-1" exists in zone "zone-a"
    And a GatewayCredential named "cred-1" exists in zone "zone-a"
    And a GatewayMeshPeer named "peer-b" exists in zone "zone-a"
    And a GatewayPolicy named "ratelimit-default" exists in zone "zone-a"
    When a snapshot for zone "zone-a" is built
    Then the snapshot contains 1 route, 1 consumer, 1 credential, 1 mesh peer, 1 policy
    And the snapshot zone spec is set

  Scenario: Resources from a different zone are not included
    Given the controller cache is empty
    And a GatewayZone "zone-a" exists
    And a GatewayRoute named "route-a1" is created in zone "zone-a"
    And a GatewayRoute named "route-b1" is created in zone "zone-b"
    When a snapshot for zone "zone-a" is built
    Then the snapshot contains 1 route, 0 consumers, 0 credentials, 0 mesh peers, 0 policies
