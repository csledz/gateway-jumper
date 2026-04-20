# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Hot-reload of plugin JARs from the watch directory
  Dropping a new plugin JAR into the configured directory must make it available
  without restarting the gateway.

  Scenario: Dropping a new plugin JAR makes it discoverable
    Given an empty plugin directory with a running watcher
    When a plugin JAR providing "hotdrop" is copied into the directory
    Then the registry eventually contains a plugin named "hotdrop"

  Scenario: Removing a plugin JAR unregisters the plugin
    Given an empty plugin directory with a running watcher
    When a plugin JAR providing "bye" is copied into the directory
    And that plugin JAR is deleted from the directory
    Then the registry eventually does not contain a plugin named "bye"
