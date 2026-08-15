package io.github.pimak.ntfy.log4j2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.ExtendedStackTraceElement;
import org.apache.logging.log4j.core.impl.ThrowableProxy;
import org.apache.logging.log4j.message.Message;

import io.github.pimak.ntfy.core.AlertEvent;

/**
 * Pure transformation from a log4j2 {@link LogEvent} into the framework-neutral {@link AlertEvent}
 * consumed by {@code AlertEngine.submit}. Stateless — no log4j2 lifecycle, no HTTP.
 *
 * <p><strong>Why the mapping happens eagerly, inside {@code append()}.</strong> Unlike Logback's
 * {@code ILoggingEvent}, a log4j2 {@code LogEvent} is <em>not</em> guaranteed to be immutable or
 * even to outlive the {@code append()} call: with async loggers/appenders the framework hands the
 * appender a recycled {@code MutableLogEvent} (or a Disruptor {@code RingBufferLogEvent}) whose
 * fields are overwritten by the next event as soon as {@code append()} returns. Snapshotting the
 * message, logger name, timestamp, cause chain, frames and marker names here — into an {@link
 * AlertEvent}, a record whose compact constructor defensively copies every collection — is what
 * makes it safe for the engine's async delivery worker or its digest timer to read the alert
 * minutes later. Nothing downstream ever holds a reference to the {@code LogEvent}.
 *
 * <p>The cause chain is walked surface&rarr;root; the root cause's frames are rendered UNLIMITED
 * (the engine applies {@code maxStackFrames}). Marker names are flattened through {@link
 * Marker#getParents()} so a marker that <em>inherits</em> from {@code NO_ALERT} still trips the
 * engine's opt-out gate.
 */
final class Log4j2EventMapper {

  /** Hard bound on the cause-chain walk (see the guard comment in {@link #map}). */
  private static final int MAX_CAUSE_DEPTH = 64;

  /** Logger name substituted for log4j2's root logger, whose name is the empty string. */
  private static final String ROOT_LOGGER_NAME = "ROOT";

  /** Body used when an event carries neither a renderable message nor a throwable to describe. */
  private static final String NO_MESSAGE = "(no message)";

  private Log4j2EventMapper() {}

  /**
   * Builds an {@link AlertEvent} from {@code event}. Never throws on a degenerate event: a blank
   * logger name, a missing/blank message and a circular cause chain are all handled, because the
   * {@link AlertEvent} constructor rejects blank mandatory fields and this mapper runs on the
   * application's logging thread.
   */
  // ThrowableProxy and LogEvent.getThrownProxy() are deprecated for REMOVAL IN LOG4J 3, but they
  // remain the only way a 2.x adapter can recover the throwable of a serialized/relayed event —
  // there is no replacement in the 2.x API. Dropping the fallback would silently degrade every
  // such alert to "no stack trace", so the deprecation is accepted and pinned here (this module
  // targets the 2.x line; see the log4j2.version comment in the reactor pom).
  @SuppressWarnings("deprecation")
  static AlertEvent map(LogEvent event) {
    List<AlertEvent.Cause> causeChain = new ArrayList<>();
    List<String> rootCauseFrames = new ArrayList<>();

    Throwable thrown = event.getThrown();
    if (thrown != null) {
      collectFromThrowable(thrown, causeChain, rootCauseFrames);
    } else {
      // No live Throwable: the event was serialized (SocketAppender, JMS, the JSON layouts) or
      // relayed, and log4j2 kept only the pre-rendered ThrowableProxy. Reading it produces exactly
      // the same AlertEvent shape, so a relayed event alerts as richly as a locally-thrown one.
      // Order matters: getThrownProxy() lazily BUILDS a proxy when a Throwable is present, so it is
      // only consulted once the cheap path is known to be empty.
      ThrowableProxy proxy = event.getThrownProxy();
      if (proxy != null) {
        collectFromProxy(proxy, causeChain, rootCauseFrames);
      }
    }

    return new AlertEvent(
        loggerName(event),
        formattedMessage(event, causeChain),
        event.getTimeMillis(),
        causeChain,
        rootCauseFrames,
        markerNames(event.getMarker()));
  }

  /**
   * Walks a live {@link Throwable} chain surface&rarr;root, appending one {@code (className,
   * message)} link per level and rendering the ROOT cause's frames.
   *
   * <p>Cycle/depth guard mirroring {@code Throwable.printStackTrace}'s {@code dejaVu} handling: a
   * circular cause chain is legally constructible ({@code a.initCause(b); b.initCause(a)}) and a
   * custom {@code getCause()} can mint fresh instances forever — without both guards this walk
   * would spin until OOM on the logging thread.
   */
  private static void collectFromThrowable(
      Throwable thrown, List<AlertEvent.Cause> causeChain, List<String> rootCauseFrames) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = thrown;
    Throwable root = thrown;
    while (current != null && causeChain.size() < MAX_CAUSE_DEPTH && seen.add(current)) {
      causeChain.add(new AlertEvent.Cause(current.getClass().getName(), current.getMessage()));
      root = current;
      current = current.getCause();
    }
    for (StackTraceElement frame : root.getStackTrace()) {
      rootCauseFrames.add(frame.toString());
    }
  }

  /**
   * The {@link ThrowableProxy} equivalent of {@link #collectFromThrowable}: same surface&rarr;root
   * order, same depth and identity-based cycle guards (a proxy chain is deserialized data and can
   * be just as circular as a live one), same unlimited ROOT frames.
   *
   * <p>Frames come from {@link ExtendedStackTraceElement#getStackTraceElement()} rather than the
   * proxy element's own {@code toString()}: the extended form appends log4j2's classloader/jar
   * annotation ({@code ~[classes/:?]}), which would make the two throwable paths render
   * differently and waste the alert's frame budget on packaging noise.
   *
   * <p>One unavoidable difference from the live path: a nested proxy stores only the frames it does
   * NOT share with its enclosing throwable (log4j2's "... N more" trimming, applied when the proxy
   * was built and irreversible afterwards). When a cause was constructed on the same line as its
   * wrapper, that leaves the ROOT proxy with zero frames — so fall back to the SURFACE proxy's
   * trace, which is never trimmed and whose tail is exactly the frames the root shares with it. An
   * alert with the shared frames beats an alert with no stack trace at all.
   */
  @SuppressWarnings("deprecation") // ThrowableProxy: see the note on map(LogEvent).
  private static void collectFromProxy(
      ThrowableProxy proxy, List<AlertEvent.Cause> causeChain, List<String> rootCauseFrames) {
    Set<ThrowableProxy> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ThrowableProxy current = proxy;
    ThrowableProxy root = proxy;
    while (current != null && causeChain.size() < MAX_CAUSE_DEPTH && seen.add(current)) {
      causeChain.add(new AlertEvent.Cause(current.getName(), current.getMessage()));
      root = current;
      current = current.getCauseProxy();
    }
    ExtendedStackTraceElement[] frames = root.getExtendedStackTrace();
    if (frames == null || frames.length == 0) {
      frames = proxy.getExtendedStackTrace();
    }
    if (frames != null) {
      for (ExtendedStackTraceElement frame : frames) {
        StackTraceElement element = frame.getStackTraceElement();
        rootCauseFrames.add(element == null ? frame.toString() : element.toString());
      }
    }
  }

  /**
   * The event's logger name, falling back to {@code "ROOT"}. log4j2 names its root logger with the
   * EMPTY STRING (not {@code null}, and not {@code "ROOT"} as Logback does), so a root-logger
   * install — exactly what {@code NtfyLog4j2Installer} performs — would otherwise hand the {@link
   * AlertEvent} constructor a blank mandatory field and blow up inside {@code append()}.
   */
  private static String loggerName(LogEvent event) {
    String name = event.getLoggerName();
    return name == null || name.isBlank() ? ROOT_LOGGER_NAME : name;
  }

  /**
   * The rendered message, with a deterministic fallback for the blank case.
   *
   * <p>A blank message is realistic in log4j2 — {@code logger.error("", e)} and a hand-built {@code
   * SimpleMessage("")} both produce one, and {@code getMessage()} itself may be {@code null} on a
   * degenerate event — while {@link AlertEvent} rejects a blank {@code formattedMessage}. Rather
   * than throw on the logging thread, describe the alert with its surface cause ({@code
   * ClassName: message}); with no throwable either, fall back to the literal {@code "(no
   * message)"} so the notification still says something.
   */
  private static String formattedMessage(LogEvent event, List<AlertEvent.Cause> causeChain) {
    Message message = event.getMessage();
    String formatted = message == null ? null : message.getFormattedMessage();
    if (formatted != null && !formatted.isBlank()) {
      return formatted;
    }
    if (!causeChain.isEmpty()) {
      AlertEvent.Cause surface = causeChain.get(0);
      String className = surface.className() == null ? "" : surface.className();
      String causeMessage = surface.message();
      String rendered =
          causeMessage == null || causeMessage.isBlank()
              ? className
              : className + ": " + causeMessage;
      if (!rendered.isBlank()) {
        return rendered;
      }
    }
    return NO_MESSAGE;
  }

  /**
   * Flattens the event's marker and every marker it inherits from into a set of names, so the
   * engine's {@code markerNames.contains("NO_ALERT")} gate fires for {@code
   * MarkerManager.getMarker("SILENT").addParents(NO_ALERT)} and not only for a directly attached
   * {@code NO_ALERT}. Unlike JUL, log4j2 HAS markers, so the per-call opt-out works on this adapter.
   */
  private static Set<String> markerNames(Marker marker) {
    if (marker == null) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    addMarkerRecursively(marker, names);
    return names;
  }

  private static void addMarkerRecursively(Marker marker, Set<String> names) {
    if (marker == null || !names.add(marker.getName())) {
      // Null guard plus cycle guard: a marker already present short-circuits, so a parent graph
      // that (directly or transitively) references itself cannot loop forever.
      return;
    }
    Marker[] parents = marker.getParents();
    if (parents != null) {
      for (Marker parent : parents) {
        addMarkerRecursively(parent, names);
      }
    }
  }
}
