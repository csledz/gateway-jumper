# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0
Feature: Migrate Kong service and route to GatewayRoute CRD

  Scenario: A single Kong service with one route produces a GatewayRoute
    Given the Kong decK fixture "simple-service-route.yaml"
    When I run "migrate"
    Then the CLI exit code is 0
    And a "GatewayRoute" resource named "echo-route" is emitted
    And the emitted "GatewayRoute" "echo-route" has upstream host "echo.default.svc"
    And the emitted "GatewayRoute" "echo-route" matches path "/echo"
