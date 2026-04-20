# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: B3 tracing propagation and secret redaction

  Scenario: B3 headers are honoured and a span is created
    When a client calls "GET" "/actuator/health" with B3 trace header "0000000000000001-0000000000000002-1"
    Then the response status is 200
    And a span context is available for that request

  Scenario: Sensitive query parameters are stripped from span URIs
    Given the redactor is configured with defaults
    When the URL "https://gw.example/path?access_token=abc&foo=bar&X-Amz-Signature=xyz&sig=123" is filtered
    Then the filtered URL contains "foo=bar"
    And the filtered URL does not contain "access_token"
    And the filtered URL does not contain "X-Amz-Signature"
    And the filtered URL does not contain "sig=123"

  Scenario: Sensitive headers are redacted
    Given the redactor is configured with defaults
    Then the header "Authorization" value "Bearer secret" is redacted to "[redacted]"
    And the header "X-Api-Key" value "abc" is redacted to "[redacted]"
    And the header "X-Request-Id" value "keep-me" is not redacted
