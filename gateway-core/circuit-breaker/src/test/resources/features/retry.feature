# SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Per-route retry with exponential backoff
  The gateway-core retry filter retries transient 5xx responses for idempotent methods,
  never retries non-idempotent methods by default, and honours exponential backoff timing.

  Scenario: Transient 503 is retried and eventually succeeds
    Given a retry policy with max 3 attempts, initial backoff 50 ms, multiplier 2.0, retry on statuses "502, 503, 504"
    And the upstream for "/flaky" returns 503 2 times then 200
    When I send a GET request to "/retry/flaky"
    Then the response status is 200
    And the upstream received 3 GET requests for "/flaky"

  Scenario: Non-idempotent POST is not retried by default
    Given a retry policy with max 3 attempts, initial backoff 50 ms, multiplier 2.0, retry on statuses "502, 503, 504"
    And the upstream POST for "/write" returns 503
    When I send a POST request to "/retry/write"
    Then the response status is 503
    And the upstream received 1 POST requests for "/write"

  Scenario: Exponential backoff is observed between attempts
    Given a retry policy with max 2 attempts, initial backoff 200 ms, multiplier 2.0, retry on statuses "503"
    And the upstream for "/slow-flaky" returns 503 2 times then 200
    When I send a GET request to "/retry/slow-flaky"
    Then the response status is 200
    And the upstream received 3 GET requests for "/slow-flaky"
    And the request took at least 200 ms
