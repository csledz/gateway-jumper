# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0
Feature: Diff reports unmigrated plugins and exits non-zero

  Scenario: diff exits with code 2 when unmigrated plugins are present
    Given the Kong decK fixture "plugins-unmigrated.yaml"
    When I run "diff"
    Then the CLI exit code is 2
    And the stdout contains "Unmigrated Kong plugins"
    And the stdout contains "prometheus"
    And the stdout contains "ip-restriction"
    And the stdout contains "pre-function"

  Scenario: diff exits with code 0 when everything is mapped
    Given the Kong decK fixture "plugins-supported.yaml"
    When I run "diff"
    Then the CLI exit code is 0
    And the stdout contains "All Kong plugins mapped successfully."
