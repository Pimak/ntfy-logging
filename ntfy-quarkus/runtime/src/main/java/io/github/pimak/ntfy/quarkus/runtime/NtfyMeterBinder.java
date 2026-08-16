package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.PipelineCounters;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.inject.Singleton;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Binds the ntfy pipeline observability counters to Micrometer as three {@link FunctionCounter}s:
 * {@code ntfy.pipeline.published}, {@code ntfy.pipeline.suppressed}, and {@code
 * ntfy.pipeline.failed} — the same three meters, with the same names and meanings, that the Spring
 * Boot starter exports.
 *
 * <p>This is a plain CDI {@link MeterBinder}: the Quarkus Micrometer extension discovers every
 * {@code MeterBinder} bean and applies it to the registry it creates, so nothing here has to know
 * which registry implementation (Prometheus, OTLP, …) the application chose.
 *
 * <p><strong>Registered conditionally.</strong> The bean is only added to the container when the
 * application actually has the Quarkus Micrometer extension: {@code NtfyProcessor} gates its {@code
 * AdditionalBeanBuildItem} on {@code Capability.METRICS} — {@code io.quarkus.metrics}, the
 * capability {@code quarkus-micrometer} provides — together with a runtime-classpath check for
 * {@code MeterRegistry}. It names this class by string, never by class literal, so on a build
 * without Micrometer this class is never loaded and its {@code io.micrometer} references never have
 * to resolve. {@code micrometer-core} is therefore an <em>optional</em> dependency of the extension:
 * an application that does not want metrics pulls in nothing.
 *
 * <p><strong>Counters are resolved lazily, at scrape time,</strong> through {@link
 * NtfyPipelineCountersHolder}. That decouples the meters from the log handler's lifecycle: the
 * handler is installed at {@code RUNTIME_INIT}, well before the CDI container (and hence this bean)
 * exists, and when the config is inactive it is never installed at all. Reading through the holder
 * on every scrape means there is no ordering dependency in either direction, and the meters simply
 * report {@code 0} when no handler is installed instead of failing or disappearing.
 *
 * <p>The counters are monotonic for a handler's lifetime, so within one application run the meters
 * only ever climb. A dev-mode reload installs a new handler with fresh counters, and the meters —
 * which resolve through the holder rather than capturing an instance — follow it down to zero and
 * back up. That is an ordinary counter reset, indistinguishable to a monitoring system from the
 * process itself restarting.
 */
@Singleton
public class NtfyMeterBinder implements MeterBinder {

  // Strong reference kept for this (singleton) bean's lifetime: Micrometer's FunctionCounter holds
  // only a WEAK reference to its state object, so without this field the supplier would be GC'd and
  // every meter would report 0.
  private final Supplier<PipelineCounters> countersSupplier = NtfyPipelineCountersHolder::get;

  @Override
  public void bindTo(MeterRegistry registry) {
    register(registry, "ntfy.pipeline.published",
        PipelineCounters::published,
        "Notifications the ntfy alert pipeline successfully published (individual + digest).");
    register(registry, "ntfy.pipeline.suppressed",
        PipelineCounters::suppressed,
        "Events the ntfy alert pipeline rate-limiter suppressed (over the per-window allowance).");
    register(registry, "ntfy.pipeline.failed",
        PipelineCounters::failed,
        "Failed ntfy publish attempts (individual publish failure/exception + digest failure).");
  }

  private void register(MeterRegistry registry, String name,
      ToDoubleFunction<PipelineCounters> reader, String description) {
    FunctionCounter.builder(name, countersSupplier, supplier -> {
          PipelineCounters counters = supplier.get();
          return counters == null ? 0d : reader.applyAsDouble(counters);
        })
        .description(description)
        .register(registry);
  }
}
