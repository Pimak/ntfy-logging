package io.github.pimak.ntfy.spring;

import io.github.pimak.ntfy.logback.LogbackAlertAppender;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Binds the ntfy pipeline observability counters to Micrometer as three monotonic {@link
 * FunctionCounter}s: {@code ntfy.pipeline.published}, {@code ntfy.pipeline.suppressed}, and {@code
 * ntfy.pipeline.failed}.
 *
 * <p><strong>All Micrometer type references live here and only here.</strong> This class is never
 * reachable from a {@link NtfyAutoConfiguration} method signature or field — a {@code @Bean}
 * parameter or return type referencing a missing Micrometer class would throw {@code
 * NoClassDefFoundError} during configuration-class introspection even when the bean is conditional.
 * The nested {@code @ConditionalOnClass(MeterRegistry.class)} configuration in the auto-config is
 * the only thing that names this class, so it is loaded only when Micrometer is present.
 *
 * <p>The counter value functions resolve the current appender <em>lazily at scrape time</em> through
 * a supplier over the auto-config's {@code installedAppender} field. That means: no ordering
 * dependency on when the appender is installed relative to binder creation; a re-install
 * automatically redirects the meters to the new appender's counters; and if no appender is installed
 * (blank url/topic, non-Logback backend, or after context shutdown) the functions simply return 0.
 * A Spring re-install builds a fresh {@code LogbackAlertAppender} with fresh counters, but {@link
 * FunctionCounter} ignores decreases in its source, so the exported metric never goes backwards.
 */
final class NtfyMetricsBinder {

  // Strong reference kept for the binder's (singleton) lifetime: Micrometer's FunctionCounter holds
  // only a WEAK reference to its state object, so without this field the supplier would be GC'd and
  // every meter would report 0.
  private final Supplier<LogbackAlertAppender> appenderSupplier;

  NtfyMetricsBinder(MeterRegistry registry, Supplier<LogbackAlertAppender> appenderSupplier) {
    this.appenderSupplier = appenderSupplier;
    register(registry, "ntfy.pipeline.published", appenderSupplier,
        a -> a.getCounters().published(),
        "Notifications the ntfy alert pipeline successfully published (individual + digest).");
    register(registry, "ntfy.pipeline.suppressed", appenderSupplier,
        a -> a.getCounters().suppressed(),
        "Events the ntfy alert pipeline rate-limiter suppressed (over the per-window allowance).");
    register(registry, "ntfy.pipeline.failed", appenderSupplier,
        a -> a.getCounters().failed(),
        "Failed ntfy publish attempts (individual publish failure/exception + digest failure).");
  }

  private static void register(MeterRegistry registry, String name,
      Supplier<LogbackAlertAppender> appenderSupplier,
      ToDoubleFunction<LogbackAlertAppender> reader, String description) {
    FunctionCounter.builder(name, appenderSupplier, supplier -> {
          LogbackAlertAppender appender = supplier.get();
          return appender == null ? 0d : reader.applyAsDouble(appender);
        })
        .description(description)
        .register(registry);
  }
}
