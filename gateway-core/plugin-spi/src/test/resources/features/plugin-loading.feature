# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: SPI discovery and ordering
  The plugin loader must discover plugins via META-INF/services,
  deduplicate by name, and expose them ordered by their `order()` within a stage.

  Scenario: Bundled X-Request-Id plugin is discovered from the classpath
    Given an empty plugin directory
    When the loader scans for plugins
    Then the registry contains a plugin named "x-request-id"
    And the plugin "x-request-id" targets stage "PRE_ROUTING"

  Scenario: Plugins at the same stage are sorted by order ascending
    Given an empty plugin directory
    And a test plugin "alpha" at stage "PRE_UPSTREAM" with order 500 is registered
    And a test plugin "beta" at stage "PRE_UPSTREAM" with order 50 is registered
    And a test plugin "gamma" at stage "PRE_UPSTREAM" with order 250 is registered
    When the registry is queried for stage "PRE_UPSTREAM"
    Then the plugin names in order are "beta, gamma, alpha"

  Scenario: Duplicate plugin names keep the first-seen entry
    Given an empty plugin directory
    And a test plugin "dupe" at stage "POST_UPSTREAM" with order 10 is registered
    And a test plugin "dupe" at stage "POST_UPSTREAM" with order 20 is registered
    When duplicate-aware de-duplication runs
    Then only one plugin named "dupe" is registered
    And that plugin has order 10
