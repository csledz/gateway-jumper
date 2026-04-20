# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: SpEL policy evaluation
  The SpEL evaluator enforces per-route authorization expressed as expressions
  that receive the PolicyContext as the root object.

  Scenario: allow request that carries the required scope
    Given a SpEL policy "read-scope" with source "hasScope('read')"
    And a request with scopes "read,write" and method "GET" on path "/api/v1/resources"
    When the policy is evaluated
    Then the decision is allow

  Scenario: deny when the tenant claim does not match the path segment
    Given a SpEL policy "tenant-match" with source "claim('tenant') == 'acme'"
    And a request with claim "tenant" set to "globex" and method "GET" on path "/api/v1/tenants/acme"
    When the policy is evaluated
    Then the decision is deny with reason "policy:tenant-match"

  Scenario: allow with header-injection obligation
    Given a SpEL policy "inject-tenant" with source "{allowed: hasScope('read'), reason: 'ok', obligations: {'add_header:X-Tenant': claim('tenant')}}"
    And a request with scopes "read" and claim "tenant" set to "acme" and method "GET" on path "/api/v1/resources"
    When the policy is evaluated through the filter
    Then the upstream request contains header "X-Tenant" with value "acme"
    And the filter chain proceeds
