// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.observability.logging;

import io.telekom.gateway.observability.ObservabilityConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Preserves MDC across reactor operator boundaries.
 *
 * <p>Registers a lifted operator that reads known MDC keys from the reactor {@link Context} and
 * re-populates SLF4J's MDC whenever a reactor signal is delivered. This keeps {@code traceId},
 * {@code spanId}, {@code route} and {@code zone} visible in log lines no matter which thread the
 * reactor scheduler hops to.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ReactorMdcPropagation {

  private static final String HOOK_KEY = "io.telekom.gateway.observability.mdc-propagation";
  private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

  /** MDC keys that are mirrored into / out of the reactor {@link Context}. */
  public static final List<String> MDC_KEYS =
      List.of(
          ObservabilityConstants.MDC_TRACE_ID,
          ObservabilityConstants.MDC_SPAN_ID,
          ObservabilityConstants.MDC_ROUTE,
          ObservabilityConstants.MDC_ZONE);

  @PostConstruct
  void register() {
    if (!INSTALLED.compareAndSet(false, true)) {
      return;
    }
    Hooks.onEachOperator(HOOK_KEY, Operators.lift((scannable, subscriber) -> wrap(subscriber)));
    log.debug("Reactor MDC propagation hook installed for keys: {}", MDC_KEYS);
  }

  @PreDestroy
  void unregister() {
    if (INSTALLED.compareAndSet(true, false)) {
      Hooks.resetOnEachOperator(HOOK_KEY);
    }
  }

  private static <T> CoreSubscriber<T> wrap(CoreSubscriber<? super T> actual) {
    return new MdcSubscriber<>(actual);
  }

  /**
   * Helper for pipelines that want to explicitly populate reactor {@link Context} from the current
   * MDC.
   */
  public static Function<Context, Context> writeMdcToContext() {
    return ctx -> {
      Context out = ctx;
      Map<String, String> current = MDC.getCopyOfContextMap();
      if (current == null) {
        return out;
      }
      for (String key : MDC_KEYS) {
        String v = current.get(key);
        if (v != null) {
          out = out.put(key, v);
        }
      }
      return out;
    };
  }

  private static final class MdcSubscriber<T> implements CoreSubscriber<T>, Scannable {
    private final CoreSubscriber<? super T> delegate;

    MdcSubscriber(CoreSubscriber<? super T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Context currentContext() {
      return delegate.currentContext();
    }

    @Override
    public void onSubscribe(Subscription s) {
      withMdc(() -> delegate.onSubscribe(s));
    }

    @Override
    public void onNext(T t) {
      withMdc(() -> delegate.onNext(t));
    }

    @Override
    public void onError(Throwable t) {
      withMdc(() -> delegate.onError(t));
    }

    @Override
    public void onComplete() {
      withMdc(delegate::onComplete);
    }

    @Override
    public Object scanUnsafe(Attr key) {
      if (key == Attr.ACTUAL) {
        return delegate;
      }
      return null;
    }

    private void withMdc(Runnable r) {
      Context ctx = delegate.currentContext();
      Map<String, String> previous = MDC.getCopyOfContextMap();
      try {
        for (String key : MDC_KEYS) {
          Object v = ctx.hasKey(key) ? ctx.get(key) : null;
          if (v != null) {
            MDC.put(key, v.toString());
          }
        }
        r.run();
      } finally {
        if (previous == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(previous);
        }
      }
    }
  }
}
