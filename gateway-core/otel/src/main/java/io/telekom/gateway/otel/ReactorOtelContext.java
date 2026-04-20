// SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package io.telekom.gateway.otel;

import io.opentelemetry.context.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;

/**
 * Installs a Reactor operator hook that propagates the current {@link Context} across reactor
 * operators.
 *
 * <p>Without this, the OTel context is lost when reactor schedules work on a different thread,
 * breaking traceable spans across WebFlux/Netty handlers. The hook is registered once, keyed by a
 * unique name so double registration is a no-op.
 *
 * <p>Reactive-safe: the hook itself performs no blocking work; it only copies the {@link Context}
 * into a reactor {@code Context} that downstream operators can read.
 */
@Slf4j
@Component
public class ReactorOtelContext {

  static final String HOOK_KEY = "gateway-core-otel-context";

  private final AtomicBoolean installed = new AtomicBoolean(false);

  @PostConstruct
  public void install() {
    if (!installed.compareAndSet(false, true)) {
      return;
    }
    Hooks.onEachOperator(
        HOOK_KEY,
        Operators.lift(
            (scannable, coreSubscriber) -> {
              Context current = Context.current();
              reactor.util.context.Context reactorCtx = coreSubscriber.currentContext();
              // If the subscriber chain has never seen an OTel context, seed it; otherwise leave
              // the
              // existing one in place (it was already propagated by an upstream operator).
              if (!reactorCtx.hasKey(Context.class)) {
                return new OtelContextSubscriber<>(coreSubscriber, current);
              }
              return coreSubscriber;
            }));
    log.info("Reactor → OTel context hook installed (key={})", HOOK_KEY);
  }

  @PreDestroy
  public void remove() {
    if (!installed.compareAndSet(true, false)) {
      return;
    }
    Hooks.resetOnEachOperator(HOOK_KEY);
    log.debug("Reactor → OTel context hook removed (key={})", HOOK_KEY);
  }

  boolean isInstalled() {
    return installed.get();
  }

  /**
   * Subscriber that splices the captured OTel {@link Context} into the reactor context visible to
   * downstream signals. The subscriber delegates every signal to the wrapped one; the reactor
   * runtime will call {@link #currentContext()} whenever it needs the augmented view.
   */
  private static final class OtelContextSubscriber<T> implements reactor.core.CoreSubscriber<T> {

    private final reactor.core.CoreSubscriber<T> delegate;
    private final reactor.util.context.Context augmented;

    OtelContextSubscriber(reactor.core.CoreSubscriber<T> delegate, Context otelContext) {
      this.delegate = delegate;
      this.augmented = delegate.currentContext().put(Context.class, otelContext);
    }

    @Override
    public reactor.util.context.Context currentContext() {
      return augmented;
    }

    @Override
    public void onSubscribe(org.reactivestreams.Subscription subscription) {
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(T t) {
      delegate.onNext(t);
    }

    @Override
    public void onError(Throwable throwable) {
      delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }
}
