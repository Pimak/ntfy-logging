package io.github.pimak.ntfy.logback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Marker;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import io.github.pimak.ntfy.core.AlertEvent;

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
   */
  static AlertEvent map(ILoggingEvent event) {
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

    return new AlertEvent(
        event.getLoggerName(),
        event.getFormattedMessage(),
        event.getTimeStamp(),
        List.copyOf(causeChain),
        List.copyOf(rootCauseFrames),
        markerNames);
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
