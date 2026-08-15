package io.github.pimak.ntfy.spring;

import io.github.pimak.ntfy.core.PipelineCounters;

/**
 * One logging backend the starter knows how to install the {@code "ntfy-auto"} appender on.
 *
 * <p><strong>No logging-framework type may appear anywhere in this interface.</strong> Each
 * implementation is the sole place its framework's classes are named, and the auto-configuration
 * refers to backends only through this interface — the same isolation discipline {@link
 * NtfyMetricsBinder} applies to Micrometer, and for the same reason: a Logback or Log4j2 type
 * reachable from a signature the container introspects would raise {@code NoClassDefFoundError} on
 * a classpath that lacks it, even when the surrounding bean is conditional.
 */
interface NtfyBackend {

  /** Name of the appender every backend registers on the root logger. */
  String APPENDER_NAME = "ntfy-auto";

  /**
   * Human-readable backend name, used only in the starter's own log lines so an operator can see
   * which backend was selected.
   *
   * @return a short display name, e.g. {@code "Logback"}
   */
  String displayName();

  /**
   * Whether this backend is the one actually receiving the application's log events.
   *
   * <p>Presence on the classpath is not enough: a bridge can put both Logback and Log4j2 on it
   * while only one is bound. Each implementation answers by inspecting the live logger factory, so
   * at most one backend claims an ordinary application.
   *
   * @return {@code true} when this backend is bound and installable
   */
  boolean isActiveBackend();

  /**
   * Installs (or idempotently reinstalls) the {@code "ntfy-auto"} appender on the root logger from
   * {@code properties}. Called once all singletons are ready, so Spring-bound configuration always
   * wins over anything a zero-code auto-install may have attached earlier.
   *
   * <p>An implementation must attach the appender <strong>only</strong> once it has actually
   * started. An appender whose engine declined to activate — a malformed endpoint, or credentials
   * over plain {@code http://} under the default strict transport mode — would otherwise sit wired
   * to the root logger in a stopped state, which both backends answer with an internal error or
   * warning on every single log event.
   *
   * @param properties the bound {@code ntfy.*} configuration
   * @return {@code true} when an appender was started and attached, {@code false} when nothing was
   *     installed (the engine has already reported why on the backend's status channel)
   */
  boolean install(NtfyProperties properties);

  /**
   * The installed appender's pipeline counters, or {@code null} when nothing is installed.
   *
   * <p>Read lazily at meter-scrape time, so it must be safe to call before {@link #install} and
   * after {@link #uninstall}.
   *
   * @return the live counters, or {@code null}
   */
  PipelineCounters counters();

  /** Stops the installed appender and detaches it from the root logger. Idempotent. */
  void uninstall();
}
