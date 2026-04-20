// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.service_discovery.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.telekom.gateway.service_discovery.api.ServiceResolver;
import io.telekom.gateway.service_discovery.composite.CompositeResolver;
import io.telekom.gateway.service_discovery.dns.StaticDnsResolver;
import io.telekom.gateway.service_discovery.filter.ServiceDiscoveryFilter;
import io.telekom.gateway.service_discovery.k8s.K8sEndpointSliceResolver;
import io.telekom.gateway.service_discovery.lb.WeightedRoundRobin;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-wires the default resolver graph: DNS always on, k8s on when {@code
 * gateway.service-discovery.k8s.enabled=true}, Consul via its own {@code @ConditionalOnProperty}.
 */
@AutoConfiguration
public class ServiceDiscoveryAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public WeightedRoundRobin weightedRoundRobin() {
    return new WeightedRoundRobin();
  }

  @Bean
  @ConditionalOnMissingBean
  public StaticDnsResolver staticDnsResolver(
      @Value("${gateway.service-discovery.dns.ttl:PT30S}") Duration ttl) {
    return new StaticDnsResolver(ttl);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "gateway.service-discovery.k8s",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean
  public KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "gateway.service-discovery.k8s",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean
  public K8sEndpointSliceResolver k8sEndpointSliceResolver(KubernetesClient client) {
    return new K8sEndpointSliceResolver(client);
  }

  @Bean
  @Primary
  @ConditionalOnMissingBean(CompositeResolver.class)
  public CompositeResolver compositeResolver(List<ServiceResolver> resolvers) {
    // Filter out any CompositeResolver itself to avoid a circular dependency: Spring populates
    // List<ServiceResolver> with every bean of that type, and composite IS a ServiceResolver.
    List<ServiceResolver> leaves =
        resolvers.stream().filter(r -> !(r instanceof CompositeResolver)).toList();
    return new CompositeResolver(leaves);
  }

  @Bean
  @ConditionalOnMissingBean
  public ServiceDiscoveryFilter serviceDiscoveryFilter(
      CompositeResolver composite, WeightedRoundRobin picker) {
    return new ServiceDiscoveryFilter(composite, picker);
  }
}
