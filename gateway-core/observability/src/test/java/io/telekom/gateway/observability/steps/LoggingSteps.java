// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.steps;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.telekom.gateway.observability.logging.ReactorMdcPropagation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

public class LoggingSteps {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, String> pendingMdc = new HashMap<>();
  private Map<String, String> observedMdc;
  private ContextView capturedContext;
  private String encodedLine;

  @After
  public void cleanup() {
    MDC.clear();
    pendingMdc.clear();
  }

  @Given("MDC entry {string} is set to {string}")
  public void setMdc(String key, String value) {
    MDC.put(key, value);
    pendingMdc.put(key, value);
  }

  @When("a mono is subscribed on a different scheduler")
  public void subscribeOnScheduler() {
    Context seed = ReactorMdcPropagation.writeMdcToContext().apply(Context.empty());
    Mono<Map<String, String>> mono =
        Mono.deferContextual(
                ctx -> {
                  Map<String, String> snapshot = new HashMap<>();
                  for (String k : ReactorMdcPropagation.MDC_KEYS) {
                    if (ctx.hasKey(k)) {
                      snapshot.put(k, ctx.get(k).toString());
                    }
                  }
                  capturedContext = ctx;
                  return Mono.just(snapshot);
                })
            .subscribeOn(Schedulers.boundedElastic())
            .contextWrite(seed);
    observedMdc = mono.block(Duration.ofSeconds(5));
  }

  @Then("the MDC entries are still visible inside the mono")
  public void mdcVisible() {
    assertThat(observedMdc).isNotNull();
    for (Map.Entry<String, String> e : pendingMdc.entrySet()) {
      assertThat(observedMdc).containsEntry(e.getKey(), e.getValue());
    }
  }

  @And("the reactor context written by the helper exposes {string} as {string}")
  public void contextExposes(String key, String value) {
    assertThat(capturedContext).isNotNull();
    assertThat(capturedContext.<String>get(key)).isEqualTo(value);
  }

  @Given("a log event is emitted with MDC entry {string} set to {string}")
  public void emitEvent(String key, String value) {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(ctx);
    encoder.setIncludeMdc(true);
    encoder.start();

    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.INFO);
    event.setLoggerName("test-logger");
    event.setMessage("hello world");
    event.setTimeStamp(System.currentTimeMillis());
    event.setMDCPropertyMap(Map.of(key, value));

    byte[] bytes = encoder.encode(event);
    encodedLine = new String(bytes, StandardCharsets.UTF_8);
  }

  @Then("the encoded log line is valid JSON")
  public void validJson() throws Exception {
    JsonNode node = mapper.readTree(encodedLine);
    assertThat(node.isObject()).isTrue();
  }

  @And("the encoded log line contains field {string} with value {string}")
  public void containsField(String field, String expected) throws Exception {
    JsonNode node = mapper.readTree(encodedLine);
    assertThat(node.has(field)).isTrue();
    assertThat(node.get(field).asText()).isEqualTo(expected);
  }

  @And("the encoded log line contains field {string}")
  public void containsFieldOnly(String field) throws Exception {
    JsonNode node = mapper.readTree(encodedLine);
    assertThat(node.has(field)).isTrue();
  }
}
