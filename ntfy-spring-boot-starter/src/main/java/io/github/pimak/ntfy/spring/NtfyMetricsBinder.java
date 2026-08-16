package io.github.pimak.ntfy.spring;

import io.github.pimak.ntfy.core.PipelineCounters;
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
 * <p>The binder is typed on {@link PipelineCounters} — a {@code ntfy-core} type — rather than on a
 * concrete appender, so the same meters serve whichever logging backend the starter installed on
 * (Logback or Log4j2) without this class knowing which.
 *
 * <p>The counter value functions resolve the current counters <em>lazily at scrape time</em>
 * through a supplier over the auto-config's installed backend. That means: no ordering dependency
 * on when the appender is installed relative to binder creation; a re-install automatically
 * redirects the meters to the new appender's counters; and if no appender is installed (blank
 * url/topic, no supported backend bound, or after context shutdown) the functions simply return 0.
 *
 * <p>The counters are monotonic for an appender's lifetime, so within one installation the meters
 * only ever climb. A re-install builds a fresh appender with fresh counters and the meters follow it
 * down to zero: {@link FunctionCounter} reports whatever its source function currently returns and
 * does not clamp a decrease. That is an ordinary counter reset, indistinguishable to a monitoring
 * system from the process itself restarting.
 */
final class NtfyMetricsBinder {

  // Strong reference kept for the binder's (singleton) lifetime: Micrometer's FunctionCounter holds
  // only a WEAK reference to its state object, so without this field the supplier would be GC'd and
  // every meter would report 0.
  private final Supplier<PipelineCounters> countersSupplier;

  NtfyMetricsBinder(MeterRegistry registry, Supplier<PipelineCounters> countersSupplier) {
    this.countersSupplier = countersSupplier;
    register(registry, "ntfy.pipeline.published", countersSupplier,
        PipelineCounters::published,
        "Notifications the ntfy alert pipeline successfully published (individual + digest).");
    register(registry, "ntfy.pipeline.suppressed", countersSupplier,
        PipelineCounters::suppressed,
        "Events the ntfy alert pipeline rate-limiter suppressed (over the per-window allowance).");
    register(registry, "ntfy.pipeline.failed", countersSupplier,
        PipelineCounters::failed,
        "Failed ntfy publish attempts (individual publish failure/exception + digest failure).");
  }

  private static void register(MeterRegistry registry, String name,
      Supplier<PipelineCounters> countersSupplier,
      ToDoubleFunction<PipelineCounters> reader, String description) {
    FunctionCounter.builder(name, countersSupplier, supplier -> {
          PipelineCounters counters = supplier.get();
          return counters == null ? 0d : reader.applyAsDouble(counters);
        })
        .description(description)
        .register(registry);
  }
}
