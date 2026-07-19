package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.AlertEngine;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * A {@link Handler} that forwards SEVERE-and-above {@link LogRecord}s to the ntfy {@link
 * AlertEngine}. Quarkus installs this via a {@code LogHandlerBuildItem}, so it sees every log record
 * the application produces (JUL, JBoss LogManager, and the SLF4J/Log4j bridges all funnel through
 * {@code java.util.logging}).
 *
 * <p>A logging handler must never throw, or it can corrupt the caller's logging path; every {@link
 * #publish} is fully guarded. Level gating mirrors the ERROR-only contract of the other adapters:
 * only records at {@link Level#SEVERE} (JUL's ERROR-equivalent) or above are alerted.
 */
public final class NtfyJulHandler extends Handler {

  private final AlertEngine engine;

  /**
   * @param engine an already-{@link AlertEngine#start() started} engine; this handler owns its
   *     lifecycle and stops it on {@link #close()}.
   */
  public NtfyJulHandler(AlertEngine engine) {
    this.engine = engine;
  }

  @Override
  public void publish(LogRecord record) {
    if (record == null || !isLoggable(record)) {
      return;
    }
    if (record.getLevel().intValue() < Level.SEVERE.intValue()) {
      return;
    }
    try {
      engine.submit(JulEventMapper.map(record));
    } catch (RuntimeException e) {
      // A handler must never propagate an exception into the logging caller.
      reportError(null, e, java.util.logging.ErrorManager.GENERIC_FAILURE);
    }
  }

  @Override
  public void flush() {
    // Delivery is synchronous inside the engine; nothing is buffered here.
  }

  @Override
  public void close() {
    engine.stop();
  }
}
