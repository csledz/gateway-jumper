# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Spike recovery
  After a 5x RPS spike, the gateway's post-spike p99 should return close to its
  pre-spike baseline.

  Scenario: Gateway recovers from a short spike
    Given a FastUpstream is running
    When the spike scenario runs baseline 100 rps with a 3s spike
    Then the gateway should recover to baseline p99 within 20.0x
