# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Composite resolver dispatches by URI scheme

  Scenario: k8s scheme is routed to the k8s resolver
    Given a composite resolver wired with stub k8s and stub dns resolvers
    When the composite resolves "k8s://svc.ns:80"
    Then the "k8s" resolver is invoked
    And the "dns" resolver is not invoked

  Scenario: dns scheme is routed to the dns resolver
    Given a composite resolver wired with stub k8s and stub dns resolvers
    When the composite resolves "dns://host.example.com:80"
    Then the "dns" resolver is invoked
    And the "k8s" resolver is not invoked

  Scenario: Unknown scheme raises an error
    Given a composite resolver wired with stub k8s and stub dns resolvers
    When the composite resolves "bogus://nope:80"
    Then the composite resolution errors with "No resolver registered for scheme: bogus"

  Scenario: Weighted round-robin picks an endpoint in proportion to weights
    Given endpoints "10.0.0.1:80@weight=1,10.0.0.2:80@weight=9"
    When picking 1000 times with weighted round-robin
    Then "10.0.0.2:80" is chosen between 800 and 1000 times
