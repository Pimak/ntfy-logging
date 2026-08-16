package io.github.pimak.ntfy.core;

/**
 * The severity of an {@link AlertEvent}, as far as the alerting engine is concerned — the value
 * {@link AlertEngine} routes on.
 *
 * <p>Framework-neutral by construction: adapters map their own level type onto one of these two
 * constants (Logback/Log4j2 {@code WARN}, JUL {@code WARNING} &rarr; {@link #WARN}; {@code
 * ERROR}/{@code FATAL}/{@code SEVERE} and above &rarr; {@link #ERROR}) so no logging framework's
 * level class leaks into core.
 *
 * <p><strong>There are deliberately only two constants.</strong> Alerting is ERROR-only by default
 * and a WARN route is opt-in ({@code warn-topic}); there is intentionally no way to express "route
 * INFO" or "route DEBUG". That floor used to be a convention repeated in every adapter's gate — as
 * an enum with no sub-WARN constant it becomes a property of the type instead, so no adapter, and
 * no future one, can widen it by accident. Anything below WARN is dropped by the adapter before an
 * {@link AlertEvent} is ever built.
 */
public enum AlertLevel {

  /**
   * Warning-level. Alerted <em>only</em> when the operator configured a {@code warn-topic};
   * otherwise the adapters never submit such an event and the engine drops it if one arrives
   * anyway.
   */
  WARN,

  /**
   * Error-level and above (Log4j2 {@code FATAL}, JUL {@code SEVERE}, and any custom level above
   * them). Always alerted — this is the engine's default and only mandatory route.
   */
  ERROR
}
