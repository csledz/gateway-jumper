// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.steps;

import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.telekom.gateway.service_discovery.api.ServiceEndpoint;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain-POJO scenario scope — Cucumber/Spring lifecycle would work, but the discovery tests are
 * quick unit-ish checks so a tiny hand-rolled container keeps start-up fast and reviewable.
 */
public class TestWorld {

  // DNS
  public final Map<String, List<String>> dnsZone = new HashMap<>();
  public UnknownHostException dnsFailure;
  public List<ServiceEndpoint> lastEndpoints;

  // K8s
  public final Map<String, EndpointSlice> slices = new HashMap<>();

  // Composite invocation counters
  public final AtomicInteger k8sCalls = new AtomicInteger();
  public final AtomicInteger dnsCalls = new AtomicInteger();
  public Throwable lastError;

  // LB
  public final Map<String, AtomicInteger> pickCounts = new HashMap<>();

  public InetAddress[] lookup(String host) throws UnknownHostException {
    if (dnsFailure != null && host.equals(dnsFailure.getMessage())) {
      throw dnsFailure;
    }
    List<String> addrs = dnsZone.get(host);
    if (addrs == null || addrs.isEmpty()) {
      throw new UnknownHostException(host);
    }
    InetAddress[] out = new InetAddress[addrs.size()];
    for (int i = 0; i < addrs.size(); i++) {
      out[i] = InetAddress.getByAddress(host, parseIpv4(addrs.get(i)));
    }
    return out;
  }

  private static byte[] parseIpv4(String ip) {
    String[] parts = ip.split("\\.");
    byte[] out = new byte[4];
    for (int i = 0; i < 4; i++) {
      out[i] = (byte) Integer.parseInt(parts[i]);
    }
    return out;
  }
}
