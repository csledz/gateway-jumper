# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0

package gateway.authz

default allow = false

allow {
  input.method == "GET";
  input.scopes[_] == "read";
  input.claims.tenant == "acme"
}
