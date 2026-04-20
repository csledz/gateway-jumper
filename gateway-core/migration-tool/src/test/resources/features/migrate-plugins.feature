# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0
Feature: Migrate supported Kong plugins to GatewayPolicy CRDs

  Scenario: rate-limiting and cors produce GatewayPolicy resources
    Given the Kong decK fixture "plugins-supported.yaml"
    When I run "migrate"
    Then the CLI exit code is 0
    And a GatewayPolicy of type "ratelimit" is emitted
    And a GatewayPolicy of type "cors" is emitted
