# SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Per-route circuit breaker
  The gateway-core circuit breaker opens after repeated upstream failures
  and serves 503 without invoking the downstream until the wait window elapses.

  Scenario: Circuit trips open on repeated 500 responses
    Given a circuit breaker policy with 5 failures in 5 calls opens the circuit
    And the upstream returns 500 for "/fail"
    When I send 5 GET requests to "/cb/fail"
    Then the circuit breaker state is "OPEN"

  Scenario: Open circuit short-circuits subsequent requests with 503
    Given a circuit breaker policy with 5 failures in 5 calls opens the circuit
    And the upstream returns 500 for "/fail"
    When I send 5 GET requests to "/cb/fail"
    And I send 1 GET requests to "/cb/fail"
    Then the last response status is 503

  Scenario: Circuit transitions to HALF_OPEN after wait duration
    Given a circuit breaker policy with 5 failures in 5 calls opens the circuit
    And the upstream returns 500 for "/fail"
    When I send 5 GET requests to "/cb/fail"
    And I wait 800 ms for the circuit to transition to HALF_OPEN
    Then the circuit breaker state is "HALF_OPEN"
