package io.github.pimak.ntfy.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only, monotonic observability counters for the alert pipeline, letting operators "alert on
 * the alerter" (e.g. a spike in {@link #failed()} signalling a revoked token or a topic ACL change).
 *
 * <p>Three lightweight tallies are tracked:
 *
 * <ul>
 *   <li>{@link #published()} — notifications actually accepted by ntfy: a successful individual
 *       {@link AlertEngine#submit} publish <em>and</em> a successful storm-digest publish (the
 *       latter also covers the synchronous flush on {@link AlertEngine#stop()}).
 *   <li>{@link #suppressed()} — events the rate-limiter gate rejected (over the per-window
 *       allowance). This is the <em>only</em> increment site, so it is disjoint from {@link
 *       #failed()} and never double-counts.
 *   <li>{@link #failed()} — failed publish attempts: an unsuccessful individual publish, an
 *       unexpected exception during an individual publish, and an unsuccessful digest publish.
 * </ul>
 *
 * <p><strong>Pulled, never logged.</strong> The engine only ever increments these; nothing here is
 * emitted through {@link Diagnostics} or any logger, so surfacing them cannot re-enter the logging
 * pipeline and the loop-safe design is preserved. Callers read the values on demand.
 *
 * <p><strong>Monotonicity.</strong> The increment methods are package-private, so only the engine
 * mutates the counters; external callers can only read. Backed by plain {@link AtomicLong}, the
 * class holds no threads, resources, or static initializers and is GraalVM native-image safe. When
 * an adapter injects a single {@code PipelineCounters} instance into every engine it builds (as the
 * Logback appender does), the counters accumulate monotonically across engine {@code
 * start()}/{@code stop()} cycles and {@code LoggerContext} resets for the adapter's whole lifetime.
 */
public final class PipelineCounters {

  private final AtomicLong published = new AtomicLong();
  private final AtomicLong suppressed = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  /** An immutable, point-in-time triple of the three counters. */
  public record Snapshot(long published, long suppressed, long failed) {}

  void incrementPublished() {
    published.incrementAndGet();
  }

  void incrementSuppressed() {
    suppressed.incrementAndGet();
  }

  void incrementFailed() {
    failed.incrementAndGet();
  }

  /** Notifications actually accepted by ntfy (individual + digest publishes). */
  public long published() {
    return published.get();
  }

  /** Events rejected by the rate-limiter gate (over the per-window allowance). */
  public long suppressed() {
    return suppressed.get();
  }

  /** Failed publish attempts (individual publish failure/exception + digest publish failure). */
  public long failed() {
    return failed.get();
  }

  /**
   * A single read of all three counters. The reads are not taken under a common lock, so the triple
   * is only near-atomic — adequate for observability where operators track rates/deltas, not exact
   * cross-counter consistency at an instant.
   */
  public Snapshot snapshot() {
    return new Snapshot(published.get(), suppressed.get(), failed.get());
  }
}
