package io.github.pimak.ntfy.logback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Marker;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import io.github.pimak.ntfy.core.AlertEvent;
import io.github.pimak.ntfy.core.MdcProjector;

/**
 * Pure transformation from a Logback {@code ILoggingEvent} into the framework-neutral {@link
 * AlertEvent} consumed by {@code AlertEngine.submit}. Stateless — no Logback lifecycle, no HTTP.
 * This is the ex-{@code PayloadBuilder}: body/title formatting now lives in ntfy-core, so this
 * mapper's sole job is to snapshot the event's exception chain, root-cause frames, and marker names
 * into plain JDK types.
 */
final class LogbackEventMapper {

  private LogbackEventMapper() {}

  /**
   * Maps {@code event} onto an {@link AlertEvent}. The cause chain is walked surface&rarr;root via
   * {@link IThrowableProxy#getCause()} (each link captured as {@code (className, message)}); the
   * root cause's stack frames are rendered UNLIMITED (the engine applies {@code maxStackFrames});
   * marker names are collected recursively so a composite marker referencing {@code NO_ALERT} as a
   * child is detected. An event with no throwable yields an empty cause chain and empty frames.
   *
   * <p>{@code includeMdcKeys} is the operator's EXPLICIT allow-list of MDC keys to render into the
   * alert body — never a pattern, never a wildcard. Two properties of the code below matter:
   *
   * <ul>
   *   <li><strong>An empty (or {@code null}) allow-list never even reads the MDC.</strong> The
   *       default configuration therefore pays nothing per event: no map lookup, no copy, no
   *       projection — and, more importantly, no MDC value can reach a notification unless a
   *       key was named in configuration.
   *   <li><strong>The values come from the EVENT, not from the {@code MDC} ThreadLocal.</strong>
   *       {@link ILoggingEvent#getMDCPropertyMap()} is the snapshot Logback captured when the event
   *       was constructed, i.e. on the application thread that made the logging call. Reading
   *       {@code org.slf4j.MDC} here instead would return whatever the CURRENT thread holds —
   *       which behind an {@code AsyncAppender} (or this appender's own async delivery) is the
   *       worker thread's context: empty at best, another request's context at worst. Sourcing from
   *       the event is what makes the projection correct on every threading model.
   * </ul>
   *
   * <p>All bounding and scrubbing of the selected entries (cap, per-value truncation, control/bidi
   * character removal, blank omission, configured-order rendering) belongs to {@link MdcProjector}
   * and is deliberately NOT duplicated here — this mapper only supplies the allow-list and a lookup
   * bound to this event's captured context.
   *
   * @param event the Logback event to snapshot
   * @param includeMdcKeys the configured MDC allow-list, in rendering order; empty/{@code null}
   *     disables the feature for this event
   */
  static AlertEvent map(ILoggingEvent event, List<String> includeMdcKeys) {
    List<AlertEvent.Cause> causeChain = new ArrayList<>();
    List<String> rootCauseFrames = new ArrayList<>();

    IThrowableProxy throwableProxy = event.getThrowableProxy();
    if (throwableProxy != null) {
      IThrowableProxy root = throwableProxy;
      for (IThrowableProxy cursor = throwableProxy; cursor != null; cursor = cursor.getCause()) {
        causeChain.add(new AlertEvent.Cause(cursor.getClassName(), cursor.getMessage()));
        root = cursor;
      }
      StackTraceElementProxy[] frames = root.getStackTraceElementProxyArray();
      if (frames != null) {
        for (StackTraceElementProxy frame : frames) {
          rootCauseFrames.add(frame.getStackTraceElement().toString());
        }
      }
    }

    Set<String> markerNames = collectMarkerNames(event);

    Map<String, String> mdcValues = Map.of();
    if (includeMdcKeys != null && !includeMdcKeys.isEmpty()) {
      // Only touched once the operator named at least one key — see the Javadoc: the unconfigured
      // path must not read the diagnostic context at all.
      Map<String, String> properties;
      try {
        properties = event.getMDCPropertyMap();
      } catch (RuntimeException e) {
        // Reading the context must never break the logging call it decorates. Logback itself
        // throws here for an event whose LoggerContext has no MDC adapter (a hand-built
        // LoggingEvent, as produced by test harnesses and by frameworks that synthesize events),
        // and a third-party ILoggingEvent may throw for reasons of its own. Degrade to "no
        // context" and publish the alert without it — the same stance MdcProjector takes for a
        // per-key lookup that throws.
        properties = null;
      }
      // Nullable too: the interface makes no non-null guarantee and third-party ILoggingEvent
      // implementations (test doubles, custom async wrappers) do return null.
      if (properties != null && !properties.isEmpty()) {
        mdcValues = MdcProjector.project(includeMdcKeys, properties::get);
      }
    }

    return new AlertEvent(
        event.getLoggerName(),
        event.getFormattedMessage(),
        event.getTimeStamp(),
        List.copyOf(causeChain),
        List.copyOf(rootCauseFrames),
        markerNames,
        mdcValues);
  }

  /**
   * Flattens every marker on the event into a set of names, recursing into composite markers'
   * referenced children so an engine-side {@code markerNames.contains("NO_ALERT")} matches a
   * {@code NO_ALERT} nested inside a parent marker exactly as the old {@code Marker.contains(name)}
   * gate did.
   */
  private static Set<String> collectMarkerNames(ILoggingEvent event) {
    Set<String> names = new LinkedHashSet<>();
    List<Marker> markers = event.getMarkerList();
    if (markers != null) {
      for (Marker marker : markers) {
        addMarkerRecursively(marker, names);
      }
    }
    return names;
  }

  private static void addMarkerRecursively(Marker marker, Set<String> names) {
    if (marker == null || !names.add(marker.getName())) {
      // Null guard plus cycle guard: a marker already present short-circuits, so a composite
      // marker that (directly or transitively) references itself cannot loop forever.
      return;
    }
    for (java.util.Iterator<Marker> it = marker.iterator(); it.hasNext(); ) {
      addMarkerRecursively(it.next(), names);
    }
  }
}
