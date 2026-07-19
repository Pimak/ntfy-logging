package io.github.pimak.ntfy.core;

/**
 * Self-diagnostic sink for {@link AlertEngine}. Core never depends on a concrete logging framework,
 * so each adapter injects an implementation that routes these callbacks to its host's status
 * channel (a Logback {@code StatusManager}, a log4j2 {@code StatusLogger}, {@code System.err}, ...).
 *
 * <p>Implementations must be safe to call from multiple threads and must never throw — a diagnostic
 * failure must not disrupt alert delivery. Messages passed here are always fixed, credential-safe
 * strings; the engine never embeds a token/username/password in them.
 */
public interface Diagnostics {

  /** Records an informational lifecycle message (engine active, exclusions configured, ...). */
  void info(String msg);

  /** Records a warning (partial config, a publish failure, an invalid window fallback, ...). */
  void warn(String msg);

  /** Records an error together with the throwable that triggered it. */
  void error(String msg, Throwable t);
}
