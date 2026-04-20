// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.proxy.model;

import java.util.List;

/** Immutable snapshot of gateway configuration used by the data-plane. */
public record ConfigSnapshot(List<String> routes, List<String> consumers, List<String> policies) {}
