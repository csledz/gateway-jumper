// SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0
package io.telekom.gateway.testkit.hydra;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * JUnit 5 extension that injects a shared {@link HydraClient} into test classes.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @ExtendWith(HydraFixture.class)
 * class MyIntegrationTest {
 *     private HydraClient hydra; // populated by the extension
 *
 *     @Test
 *     void obtains_a_token() {
 *         String at = hydra.clientCredentialsToken("gateway-test-cc",
 *                                                   List.of("read")).block();
 *         // ... use `at` as a Bearer credential
 *     }
 * }
 * }</pre>
 *
 * <p>Injection targets (both supported):
 *
 * <ul>
 *   <li>test-class instance fields of type {@link HydraClient}
 *   <li>test-method parameters of type {@link HydraClient}
 * </ul>
 *
 * <p>A single {@link HydraClient} is created per JUnit engine execution and stored in the
 * extension's {@link ExtensionContext.Store}. The reactor-netty HttpClient held by {@link
 * HydraClient} is stateless, so no teardown is needed.
 */
@Slf4j
public final class HydraFixture implements TestInstancePostProcessor, ParameterResolver {

  private static final Namespace NS = Namespace.create(HydraFixture.class);
  private static final String CLIENT_KEY = "hydra-client";

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context)
      throws Exception {
    HydraClient client = clientFrom(context);
    for (Field field : fieldsOf(testInstance.getClass())) {
      if (HydraClient.class.isAssignableFrom(field.getType())) {
        field.setAccessible(true);
        if (field.get(testInstance) == null) {
          field.set(testInstance, client);
        }
      }
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
    return HydraClient.class.isAssignableFrom(parameterContext.getParameter().getType());
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
    return clientFrom(context);
  }

  private static HydraClient clientFrom(ExtensionContext context) {
    return context
        .getRoot()
        .getStore(NS)
        .getOrComputeIfAbsent(CLIENT_KEY, k -> new HydraClient(), HydraClient.class);
  }

  private static List<Field> fieldsOf(Class<?> type) {
    List<Field> out = new ArrayList<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        out.add(f);
      }
    }
    return out;
  }
}
