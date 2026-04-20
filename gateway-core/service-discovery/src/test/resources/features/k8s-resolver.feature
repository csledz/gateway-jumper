# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Kubernetes EndpointSlice resolver reacts to watch events

  Scenario: ADDED event populates the cache
    Given a k8s namespace "prod" containing an EndpointSlice "orders-abc" for service "orders" with addresses "10.0.0.1,10.0.0.2" on port 8080
    When the resolver receives an ADDED event for "orders-abc"
    Then resolving "k8s://orders.prod:8080" returns 2 healthy endpoint(s)

  Scenario: MODIFIED event updates the cache
    Given a k8s namespace "prod" containing an EndpointSlice "orders-abc" for service "orders" with addresses "10.0.0.1" on port 8080
    And the resolver has received an ADDED event for "orders-abc"
    When the EndpointSlice "orders-abc" is modified to addresses "10.0.0.1,10.0.0.2,10.0.0.3"
    Then resolving "k8s://orders.prod:8080" returns 3 healthy endpoint(s)

  Scenario: DELETED event evicts the cache
    Given a k8s namespace "prod" containing an EndpointSlice "orders-abc" for service "orders" with addresses "10.0.0.1" on port 8080
    And the resolver has received an ADDED event for "orders-abc"
    When the resolver receives a DELETED event for "orders-abc"
    Then resolving "k8s://orders.prod:8080" returns 0 healthy endpoint(s)

  Scenario: Endpoint with ready=false is marked unhealthy
    Given a k8s namespace "prod" containing an EndpointSlice "orders-abc" for service "orders" with one ready address "10.0.0.1" and one not-ready address "10.0.0.2" on port 8080
    When the resolver receives an ADDED event for "orders-abc"
    Then resolving "k8s://orders.prod:8080" returns 1 healthy endpoint(s) and 1 unhealthy endpoint(s)
