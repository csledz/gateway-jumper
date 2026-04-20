# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

Feature: Rego policy evaluation
  The optional Rego adapter loads a minimal Rego module and uses it for authz.

  Scenario: allow GET request with required scope and tenant
    Given a Rego policy "read-acme" loaded from "rego/read_acme.rego"
    And a request with scopes "read" and claim "tenant" set to "acme" and method "GET" on path "/api/v1/resources"
    When the policy is evaluated
    Then the decision is allow

  Scenario: deny when default allow is false and no rule matches
    Given a Rego policy "read-acme" loaded from "rego/read_acme.rego"
    And a request with scopes "write" and claim "tenant" set to "globex" and method "POST" on path "/api/v1/resources"
    When the policy is evaluated
    Then the decision is deny
