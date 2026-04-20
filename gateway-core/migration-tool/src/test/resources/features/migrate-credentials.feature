# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0
Feature: Migrate Kong consumer credentials

  Scenario: JWT, key-auth and basic-auth credentials are emitted as GatewayCredential CRDs
    Given the Kong decK fixture "credentials.yaml"
    When I run "migrate"
    Then the CLI exit code is 0
    And a "GatewayConsumer" resource named "alice" is emitted
    And a GatewayCredential of type "jwt" for consumer "alice" is emitted
    And a GatewayCredential of type "key-auth" for consumer "alice" is emitted
    And a GatewayCredential of type "basic-auth" for consumer "alice" is emitted
