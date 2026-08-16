package io.github.pimak.ntfy.core;

import java.util.List;
import java.util.Map;
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
 * @param mdcValues the ALLOW-LISTED MDC entries only, in configured order, already
 *     scrubbed/truncated/capped by {@link MdcProjector} — adapters must project via {@link
 *     MdcProjector#project} and never pass a raw MDC map; empty on frameworks with no MDC (plain
 *     JUL) and whenever {@code include-mdc-keys} is unset
 * @param level the route this event belongs to; {@code null} is normalized to {@link
 *     AlertLevel#ERROR}
 */
public record AlertEvent(
    String loggerName,
    String formattedMessage,
    long timestampMillis,
    List<Cause> causeChain,
    List<String> rootCauseFrames,
    Set<String> markerNames,
    Map<String, String> mdcValues,
    AlertLevel level) {

  /**
   * Validates the mandatory fields and normalizes the optional collections so every construction
   * path — direct {@code new}, the {@link #of} factory, or an adapter — yields a null-free,
   * defensively copied event.
   *
   * <p>{@code mdcValues} is re-run through {@link MdcProjector#sanitize} rather than merely copied:
   * that call is null-safe, order-preserving, and idempotent, so a projection an adapter already
   * produced passes through untouched while an event hand-built by a third party that handed over a
   * raw MDC map is still capped, scrubbed, and truncated here. Only the allow-list SELECTION is the
   * adapter's job; the safety of what ends up in a body is enforced at this boundary.
   *
   * <p>A {@code null} {@code level} normalizes to {@link AlertLevel#ERROR}, never {@link
   * AlertLevel#WARN}: an adapter (or third-party caller) that omits the field must fall back to the
   * always-on route, not silently divert its events onto the opt-in quiet channel.
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
    mdcValues = MdcProjector.sanitize(mdcValues);
    level = level == null ? AlertLevel.ERROR : level;
  }

  /**
   * The pre-{@code level} signature, kept as an explicit constructor for
   * <strong>compatibility</strong>, exactly like the pre-{@code mdcValues} one below: preserving
   * the {@code (String, String, long, List, List, Set, Map)} descriptor keeps adapters compiled
   * against an earlier release both source- AND binary-compatible. Delegates at {@link
   * AlertLevel#ERROR}, which is the only level those callers could ever have meant.
   */
  public AlertEvent(String loggerName, String formattedMessage, long timestampMillis,
      List<Cause> causeChain, List<String> rootCauseFrames, Set<String> markerNames,
      Map<String, String> mdcValues) {
    this(loggerName, formattedMessage, timestampMillis, causeChain, rootCauseFrames, markerNames,
        mdcValues, AlertLevel.ERROR);
  }

  /**
   * The pre-{@code mdcValues} canonical signature, kept as an explicit constructor for
   * <strong>compatibility</strong>: {@link AlertEvent} is public API, and preserving the original
   * {@code (String, String, long, List, List, Set)} descriptor keeps adapters compiled against an
   * earlier release both source- AND binary-compatible — they keep linking without recompilation.
   * Delegates with an empty MDC map at {@link AlertLevel#ERROR}, which is exactly the behavior
   * those callers already had.
   */
  public AlertEvent(String loggerName, String formattedMessage, long timestampMillis,
      List<Cause> causeChain, List<String> rootCauseFrames, Set<String> markerNames) {
    this(loggerName, formattedMessage, timestampMillis, causeChain, rootCauseFrames, markerNames,
        Map.of(), AlertLevel.ERROR);
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
   * rootCauseFrames}, {@code markerNames} and {@code mdcValues} default to empty collections, and
   * the event is an {@link AlertLevel#ERROR} one.
   */
  public static AlertEvent of(String loggerName, String formattedMessage, long timestampMillis) {
    return new AlertEvent(
        loggerName, formattedMessage, timestampMillis, List.of(), List.of(), Set.of(), Map.of(),
        AlertLevel.ERROR);
  }

  /**
   * Returns a copy of this event on {@code level}, leaving every other component untouched. Lets an
   * adapter build the event once and then stamp the route onto it, rather than threading the level
   * through every mapper signature.
   */
  public AlertEvent withLevel(AlertLevel level) {
    return new AlertEvent(
        loggerName, formattedMessage, timestampMillis, causeChain, rootCauseFrames, markerNames,
        mdcValues, level);
  }
}
