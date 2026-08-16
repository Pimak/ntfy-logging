package io.github.pimak.ntfy.log4j2;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Keeps the programmatically installed {@link NtfyLog4j2Appender} attached across a log4j2
 * reconfiguration — the analogue of {@code NtfyLogbackReattachListener} for a framework whose reset
 * mechanics are quite different.
 *
 * <p>Logback resets the existing {@code LoggerContext} in place; log4j2 instead builds a brand-new
 * {@link Configuration} and swaps it in. That swap silently undoes a programmatic install twice
 * over: the new {@code Configuration} was assembled from {@code log4j2.xml} and therefore knows
 * nothing about an appender added in code, AND {@code LoggerContext.setConfiguration} STOPS the
 * outgoing configuration, which stops every appender registered in it — including ours, engine
 * included. So this hook must both re-attach and, when needed, restart.
 *
 * <p><strong>Why re-starting is checked before, and independently of, re-attaching.</strong> A
 * single {@code setConfiguration} fires {@link LoggerContext#PROPERTY_CONFIG} TWICE: once from the
 * inner {@code updateLoggers()} (before the outgoing configuration is stopped) and once after the
 * swap completes (after it has been stopped). The first firing therefore sees a live appender and
 * attaches it; the second sees the very same appender attached but by then STOPPED. Folding the
 * restart into an "if not attached" branch would leave that second state untouched — a stopped
 * appender wired to the root logger, which log4j2 answers with one error per logged event. Both
 * steps are individually idempotent instead.
 */
final class NtfyLog4j2ReattachListener implements PropertyChangeListener {

  private final NtfyLog4j2Appender appender;

  NtfyLog4j2ReattachListener(NtfyLog4j2Appender appender) {
    this.appender = appender;
  }

  @Override
  public void propertyChange(PropertyChangeEvent event) {
    if (!LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())) {
      return;
    }
    if (!(event.getNewValue() instanceof Configuration configuration)) {
      return;
    }
    if (event.getSource() instanceof LoggerContext context
        && (context.isStopping() || context.isStopped())) {
      // LoggerContext.stop() installs a NullConfiguration and fires this same event on its way out.
      // Re-attaching there would resurrect the engine (executor, HTTP client, digest timer) on a
      // context that is going away — the exact leak NtfyLogbackReattachListener.onStop guards
      // against. The outgoing configuration's stop(), which runs right after, is what releases us.
      return;
    }

    if (!appender.isStarted()) {
      // Restart before touching the configuration: log4j2 refuses to dispatch to a stopped appender
      // and logs an error per event. The appender-owned PipelineCounters survive this, so tallies
      // stay monotonic across the reconfiguration.
      appender.start();
      if (!appender.isStarted()) {
        // The engine declined to activate (it already said why on the StatusLogger). Leave the
        // configuration alone rather than wire in an appender that can only complain.
        return;
      }
    }

    LoggerConfig root = configuration.getRootLogger();
    if (root.getAppenders().containsKey(appender.getName())) {
      // Already present under our name (the first of the two firings attached it, or the new
      // configuration declares an <Ntfy> element of its own) — never attach a second time.
      return;
    }
    configuration.addAppender(appender);
    // No filter, and the appender's own attach level: the same root-logger contract the installer
    // establishes, read from the same single source so a reconfiguration can never re-attach at a
    // different floor than the original install used. LoggerConfig dispatch reads this list live,
    // so no updateLoggers() call is needed here — and calling one would re-enter this listener.
    root.addAppender(appender, appender.attachLevel(), null);
  }
}
