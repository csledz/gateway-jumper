// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.model;

/**
 * Identity of a federation zone.
 *
 * @param name logical zone identifier (for example {@code zone-a})
 * @param realm realm this zone belongs to
 * @param stargateUrl public ingress URL
 * @param issuerUrl issuer URL used for OIDC discovery
 * @param internetFacing whether this zone is exposed to the public internet
 */
public record Zone(
    String name, String realm, String stargateUrl, String issuerUrl, boolean internetFacing) {}
