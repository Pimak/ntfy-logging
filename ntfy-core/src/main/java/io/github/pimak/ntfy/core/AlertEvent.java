package io.github.pimak.ntfy.core;

import java.util.List;
import java.util.Set;

/**
 * Framework-neutral snapshot of a single log event, handed to {@link AlertEngine#submit}. Every
 * field is a plain JDK type so any logging-framework adapter (logback, log4j2, JUL, ...) can
 * assemble one without leaking a framework-specific event type into core.
 *
 * @param loggerName the name of the logger that produced the event
 * @param formattedMessage the fully rendered log message (arguments already substituted)
 * @param timestampMillis event time as epoch milliseconds
 * @param causeChain the full exception chain surface&rarr;root, each element as a
 *     {@code (className, message)} pair; empty when the event carries no throwable
 * @param rootCauseFrames the ROOT cause's stack frames pre-rendered as strings (the {@code
 *     StackTraceElement.toString()} form), UNLIMITED here — the engine applies {@code
 *     maxStackFrames}; empty when the event carries no throwable
 * @param markerNames the SLF4J marker names attached to the event (flattened, including composite
 *     children); empty on frameworks without markers (e.g. JUL)
 */
public record AlertEvent(
    String loggerName,
    String formattedMessage,
    long timestampMillis,
    List<Cause> causeChain,
    List<String> rootCauseFrames,
    Set<String> markerNames) {

  /**
   * Validates the mandatory fields and normalizes the optional collections so every construction
   * path — direct {@code new}, the {@link #of} factory, or an adapter — yields a null-free,
   * defensively copied event.
   */
  public AlertEvent {
    if (loggerName == null || loggerName.isBlank()) {
      throw new IllegalArgumentException("loggerName must not be blank");
    }
    if (formattedMessage == null || formattedMessage.isBlank()) {
      throw new IllegalArgumentException("formattedMessage must not be blank");
    }
    causeChain = causeChain == null ? List.of() : List.copyOf(causeChain);
    rootCauseFrames = rootCauseFrames == null ? List.of() : List.copyOf(rootCauseFrames);
    markerNames = markerNames == null ? Set.of() : Set.copyOf(markerNames);
  }

  /**
   * One link of an exception chain: the throwable's class name and its message (either may be
   * {@code null} in degenerate cases, mirroring the source throwable).
   *
   * @param className the fully qualified class name of the throwable
   * @param message the throwable's message, or {@code null}
   */
  public record Cause(String className, String message) {}

  /**
   * Convenience factory for the "no throwable, no markers" case: {@code causeChain}, {@code
   * rootCauseFrames} and {@code markerNames} default to empty collections.
   */
  public static AlertEvent of(String loggerName, String formattedMessage, long timestampMillis) {
    return new AlertEvent(
        loggerName, formattedMessage, timestampMillis, List.of(), List.of(), Set.of());
  }
}
