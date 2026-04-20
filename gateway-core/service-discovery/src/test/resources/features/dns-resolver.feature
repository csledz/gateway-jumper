# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Static DNS resolver returns all A records and honours TTL

  Scenario: Multi-A records surface as multiple endpoints
    Given a DNS entry "api.example.com" with addresses "10.1.1.1,10.1.1.2,10.1.1.3"
    When the DNS resolver resolves "dns://api.example.com:443"
    Then the resolver returns 3 endpoint(s)

  Scenario: TTL caches results and survives upstream changes within TTL window
    Given a DNS entry "stable.example.com" with addresses "10.2.2.1"
    And the DNS TTL is 60 seconds
    When the DNS resolver resolves "dns://stable.example.com:80"
    And the DNS entry "stable.example.com" changes to addresses "10.2.2.99"
    And the DNS resolver resolves "dns://stable.example.com:80" again
    Then the resolver returns endpoint "10.2.2.1:80"

  Scenario: Unknown host yields empty list rather than error
    Given a DNS lookup that throws UnknownHostException for "missing.example.com"
    When the DNS resolver resolves "dns://missing.example.com:80"
    Then the resolver returns 0 endpoint(s)
