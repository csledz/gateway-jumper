# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Resilience under load
  With an upstream returning 5xx on 20% of requests, the gateway's retry and/or
  circuit breaker must not amplify upstream failures into a meltdown: upstream
  request volume stays within a bounded multiple of driver request volume.

  Scenario: Gateway does not amplify flaky upstream failures
    Given a FlakyUpstream is running with 20% failure rate
    When the resilience scenario runs at 150 rps for 5 seconds with concurrency 64
    Then retry amplification should stay below 3.0x
