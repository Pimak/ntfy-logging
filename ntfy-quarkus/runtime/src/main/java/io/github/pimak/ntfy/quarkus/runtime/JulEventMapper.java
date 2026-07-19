package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.AlertEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.LogRecord;

/**
 * Maps a {@link LogRecord} (Quarkus routes all logging — JBoss LogManager, JUL, and the SLF4J/Log4j
 * bridges — through {@code java.util.logging}) into the framework-neutral {@link AlertEvent} the
 * core engine consumes.
 *
 * <p>The cause chain is walked surface&rarr;root; the root cause's frames are rendered unlimited
 * (the engine applies {@code maxStackFrames}). JUL has no marker concept, so {@code markerNames} is
 * always empty.
 */
final class JulEventMapper {

  /** Hard bound on the cause-chain walk (see the guard comment in {@link #map}). */
  private static final int MAX_CAUSE_DEPTH = 64;

  private JulEventMapper() {}

  /** Builds an {@link AlertEvent} from {@code record}. Never throws on a malformed record. */
  static AlertEvent map(LogRecord record) {
    List<AlertEvent.Cause> causeChain = new ArrayList<>();
    List<String> rootCauseFrames = List.of();

    Throwable thrown = record.getThrown();
    if (thrown != null) {
      // Cycle/depth guard mirroring Throwable.printStackTrace's dejaVu handling: a circular cause
      // chain is legally constructible (a.initCause(b); b.initCause(a)) and a custom getCause()
      // can mint fresh instances forever — without both guards this walk would loop until OOM on
      // the logging thread, breaking the "never throws on a malformed record" contract.
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      Throwable current = thrown;
      Throwable root = thrown;
      while (current != null && causeChain.size() < MAX_CAUSE_DEPTH && seen.add(current)) {
        causeChain.add(new AlertEvent.Cause(current.getClass().getName(), current.getMessage()));
        root = current;
        current = current.getCause();
      }
      StackTraceElement[] frames = root.getStackTrace();
      List<String> rendered = new ArrayList<>(frames.length);
      for (StackTraceElement frame : frames) {
        rendered.add(frame.toString());
      }
      rootCauseFrames = rendered;
    }

    return new AlertEvent(
        record.getLoggerName(),
        formatMessage(record),
        record.getMillis(),
        causeChain,
        rootCauseFrames,
        Set.of());
  }

  /**
   * Renders the record's message, substituting {@link java.util.logging.LogRecord#getParameters()}
   * via {@link MessageFormat} when present. A malformed pattern falls back to the raw message rather
   * than throwing.
   */
  private static String formatMessage(LogRecord record) {
    String raw = record.getMessage();
    Object[] params = record.getParameters();
    if (raw == null || params == null || params.length == 0) {
      return raw;
    }
    try {
      return MessageFormat.format(raw, params);
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }
}
