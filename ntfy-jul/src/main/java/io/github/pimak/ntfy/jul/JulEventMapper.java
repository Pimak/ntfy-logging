package io.github.pimak.ntfy.jul;

import io.github.pimak.ntfy.core.AlertEvent;
import io.github.pimak.ntfy.core.MdcProjector;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.LogRecord;

/**
 * Maps a {@link LogRecord} into the framework-neutral {@link AlertEvent} the core engine consumes.
 * Everything that logs through {@code java.util.logging} — plain JUL, JBoss LogManager, and the
 * SLF4J/Log4j bridges — produces records this mapper understands.
 *
 * <p>The cause chain is walked surface&rarr;root; the root cause's frames are rendered unlimited
 * (the engine applies {@code maxStackFrames}). JUL has no marker concept, so {@code markerNames} is
 * always empty.
 *
 * <p>Plain JUL has no MDC concept either, so {@code mdcValues} is empty unless the record is one
 * JBoss LogManager supplied — an {@code org.jboss.logmanager.ExtLogRecord}, as under Quarkus —
 * which {@link ExtLogRecordMdc} detects reflectively. Even then, only the operator's explicitly
 * allow-listed keys are ever read: see {@link MdcProjector}, which owns every guard (order, dedupe,
 * caps, truncation, scrubbing) applied to the result.
 */
final class JulEventMapper {

  /** Hard bound on the cause-chain walk (see the guard comment in {@link #map}). */
  private static final int MAX_CAUSE_DEPTH = 64;

  private JulEventMapper() {}

  /**
   * Builds an {@link AlertEvent} from {@code record}. Never throws on a malformed record.
   *
   * @param record the JUL record to map
   * @param includeMdcKeys the operator-configured MDC allow-list, in rendering order; {@code null}
   *     or empty (the default) means the record's MDC is never consulted and no reflection happens
   *     at all
   */
  static AlertEvent map(LogRecord record, List<String> includeMdcKeys) {
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

    // The feature being unset must cost nothing: no reflection, no MDC access, not even a probe
    // for JBoss LogManager. Only once the operator has named at least one key is the record's
    // provenance examined at all.
    Map<String, String> mdcValues = Map.of();
    if (includeMdcKeys != null && !includeMdcKeys.isEmpty()) {
      Function<String, String> lookup = ExtLogRecordMdc.lookupFor(record);
      if (lookup != null) {
        mdcValues = MdcProjector.project(includeMdcKeys, lookup);
      }
    }

    return new AlertEvent(
        loggerName(record),
        formatMessage(record),
        record.getMillis(),
        causeChain,
        rootCauseFrames,
        Set.of(),
        mdcValues);
  }

  /**
   * The record's logger name, falling back to {@code "ROOT"} for anonymous loggers (whose {@link
   * LogRecord#getLoggerName()} is {@code null}), so the mapper never emits a blank mandatory field.
   */
  private static String loggerName(LogRecord record) {
    String name = record.getLoggerName();
    return name == null || name.isBlank() ? "ROOT" : name;
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
