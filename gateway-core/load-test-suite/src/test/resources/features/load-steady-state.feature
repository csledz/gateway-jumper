# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Steady-state load
  Confirm that at a moderate fixed RPS against a lightweight upstream, the gateway
  keeps p99 well inside its latency budget and never returns 5xx.

  Scenario: Moderate RPS against a FastUpstream holds p99 and never returns 5xx
    Given a FastUpstream is running
    When the steady-state scenario runs at 200 rps for 5 seconds with concurrency 64
    Then p99 latency should be below 500 ms
    And no 5xx responses should be observed
