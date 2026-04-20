# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Backpressure visibility
  When the driver exceeds the gateway's processing rate, queue-depth / pool
  saturation signals must become visible - that's how we know the gateway is the
  bottleneck and not some invisible upstream limit.

  Scenario: Overdrive produces visible backpressure signals
    Given a FastUpstream is running
    When the backpressure scenario runs at 2000 rps for 5 seconds with concurrency 32
    Then the backpressure signal should be visible in open connections or pending acquires
