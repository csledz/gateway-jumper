# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Slow-upstream connection growth
  As upstream latency rises, the gateway must hold more sockets open at a given RPS
  (Little's Law). This feature captures the connection-count trajectory and asserts
  that the correlation with upstream latency is positive; it also asserts pool
  saturation becomes visible.

  Scenario: Open-connections count rises with upstream latency
    Given a SlowUpstream is running
    When the slow-upstream scenario ramps latency from 10 ms to 2000 ms at 200 rps for 8 seconds with concurrency 512
    Then open connections should grow as upstream latency grows
    And pool saturation should be recorded
