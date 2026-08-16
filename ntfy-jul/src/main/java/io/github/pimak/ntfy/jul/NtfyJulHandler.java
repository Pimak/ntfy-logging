package io.github.pimak.ntfy.jul;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.AlertLevel;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PipelineCounters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * A {@link Handler} that forwards SEVERE-and-above (and, when a warn route is configured,
 * WARNING) {@link LogRecord}s to the ntfy {@link
 * AlertEngine}. This is the framework-neutral JUL adapter: attach it to any {@code
 * java.util.logging} logger (plain JUL apps, Tomcat, Helidon, …), or let a framework install it —
 * the Quarkus extension feeds it to a {@code LogHandlerBuildItem}, so it sees every log record the
 * application produces (JUL, JBoss LogManager, and the SLF4J/Log4j bridges all funnel through
 * {@code java.util.logging}).
 *
 * <p>For zero-code installation see {@link NtfyJulAutoHandler} (declarative, {@code
 * logging.properties}) and {@link NtfyJulInstaller} (programmatic one-liner).
 *
 * <p>A logging handler must never throw, or it can corrupt the caller's logging path; every {@link
 * #publish} is fully guarded. Level gating mirrors the other adapters: records at {@link
 * Level#SEVERE} (JUL's ERROR-equivalent) or above are always alerted, and {@link Level#WARNING}
 * records only once the engine activated a warn route ({@code warn-topic}). Nothing below WARNING
 * is ever alerted.
 */
public final class NtfyJulHandler extends Handler {

  private final AlertEngine engine;

  /**
   * The operator's {@code include-mdc-keys} allow-list, in rendering order; never {@code null},
   * empty when the feature is unset (the default), in which case no MDC is ever consulted.
   */
  private final List<String> includeMdcKeys;

  /**
   * Equivalent to {@link #NtfyJulHandler(AlertEngine, List)} with an empty MDC allow-list, i.e. no
   * MDC values in the alert body. Retained as an explicit constructor so callers written against an
   * earlier release stay both source- AND binary-compatible: the {@code (AlertEngine)} descriptor
   * keeps linking without recompilation, and its behavior is unchanged.
   *
   * @param engine an already-{@link AlertEngine#start() started} engine; this handler owns its
   *     lifecycle and stops it on {@link #close()}.
   */
  public NtfyJulHandler(AlertEngine engine) {
    this(engine, List.of());
  }

  /**
   * @param engine an already-{@link AlertEngine#start() started} engine; this handler owns its
   *     lifecycle and stops it on {@link #close()}.
   * @param includeMdcKeys the MDC keys whose values may be rendered into the alert body, in
   *     rendering order — an explicit allow-list with no wildcard form; {@code null} or empty means
   *     no MDC value is ever read. Defensively copied, so a later mutation of the caller's list
   *     cannot widen what this handler publishes.
   */
  public NtfyJulHandler(AlertEngine engine, List<String> includeMdcKeys) {
    this.engine = engine;
    // unmodifiableList(new ArrayList<>(..)), not List.copyOf: a stray null element in an
    // operator-supplied list must not blow up handler INSTALLATION. MdcProjector already skips
    // null/blank keys, so the tolerant copy degrades exactly one key instead of the whole handler.
    this.includeMdcKeys =
        includeMdcKeys == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(includeMdcKeys));
  }

  /**
   * Builds a handler around a fresh, started {@link AlertEngine} for {@code config}, with engine
   * self-diagnostics routed to {@code System.err} (never back through the logging pipeline — see
   * {@link JulDiagnostics}). The single construction path shared by the programmatic installer,
   * the auto-handler, and the Quarkus extension's recorder.
   *
   * <p>{@code config} should be {@link NtfyConfig#isActive() active}; an inactive or invalid
   * config never throws — the engine refuses activation with a diagnostic and the returned
   * handler simply drops records.
   *
   * <p>Because this is the single construction path, forwarding {@link
   * NtfyConfig#getIncludeMdcKeys()} here is the ONE change that wires {@code include-mdc-keys}
   * through every installation route at once: {@link NtfyJulInstaller}, {@link NtfyJulAutoHandler}
   * and the Quarkus extension's recorder all build their handler through {@code forConfig}, so none
   * of them needs to know the option exists.
   */
  public static NtfyJulHandler forConfig(NtfyConfig config) {
    AlertEngine engine = new AlertEngine(config, new JulDiagnostics());
    engine.start();
    return new NtfyJulHandler(engine, config.getIncludeMdcKeys());
  }

  /**
   * The alert pipeline's observability counters for this handler's engine — the JUL counterpart of
   * the Logback/Log4j2 appenders' {@code getCounters()}. Read-only and pulled, never logged, so
   * surfacing them (in a Micrometer meter, a health check, a JMX bean, …) cannot re-enter the
   * logging pipeline.
   *
   * <p>Unlike the appenders, a handler owns exactly one engine for its whole lifetime — it is
   * constructed around an already-started engine and stops it on {@link #close()} — so the returned
   * instance is stable and its tallies are monotonic for as long as the handler is installed.
   * Re-installing the handler (a new {@code forConfig}) yields a new engine with fresh counters.
   */
  public PipelineCounters counters() {
    return engine.counters();
  }

  @Override
  public void publish(LogRecord record) {
    if (record == null || !isLoggable(record)) {
      return;
    }
    // SEVERE and above always alert. WARNING alerts only when the engine activated a warn route;
    // below WARNING nothing ever does, on any configuration. Ordered so the SEVERE path costs
    // exactly what it did before the warn route existed — the extra check is reached only by
    // sub-SEVERE records. `isWarnRoutingActive()` is read live rather than cached at construction:
    // it is a volatile read on the engine, and this handler accepts an engine through a public
    // constructor whose start() it does not control.
    int level = record.getLevel().intValue();
    AlertLevel routed;
    if (level >= Level.SEVERE.intValue()) {
      routed = AlertLevel.ERROR;
    } else if (level >= Level.WARNING.intValue() && engine.isWarnRoutingActive()) {
      routed = AlertLevel.WARN;
    } else {
      return;
    }
    try {
      engine.submit(JulEventMapper.map(record, includeMdcKeys).withLevel(routed));
    } catch (RuntimeException e) {
      // A handler must never propagate an exception into the logging caller.
      reportError(null, e, java.util.logging.ErrorManager.GENERIC_FAILURE);
    }
  }

  @Override
  public void flush() {
    // Nothing to flush here: in synchronous mode delivery completes inline, and in async mode the
    // engine's own bounded queue buffers pending alerts and is drained on close()/stop().
  }

  @Override
  public void close() {
    engine.stop();
  }
}
