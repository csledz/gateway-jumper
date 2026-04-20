// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.mesh_federation.model;

/**
 * Federation peer of the local zone.
 *
 * @param peerZone remote zone name
 * @param endpoint base URL used for data-plane forwarding
 * @param tokenEndpoint URL of the peer token endpoint
 * @param mtls whether mutual TLS is required on the control channel
 */
public record MeshPeer(String peerZone, String endpoint, String tokenEndpoint, boolean mtls) {}
