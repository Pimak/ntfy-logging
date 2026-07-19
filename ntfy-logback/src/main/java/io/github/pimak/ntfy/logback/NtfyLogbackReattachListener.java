package io.github.pimak.ntfy.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggerContextListener;

/**
 * Keeps the auto-installed {@link LogbackAlertAppender} attached to the root logger across a {@code
 * LoggerContext} reset. When an application later runs its own {@code JoranConfigurator} (loading a
 * {@code logback.xml}), Logback resets the context — detaching every programmatically-added appender
 * and normally discarding non-reset-resistant listeners. This listener declares itself {@link
 * #isResetResistant() reset-resistant} so it survives the reset, then re-attaches (and restarts, if
 * stopped) the ntfy appender on {@code onReset}/{@code onStart}.
 */
final class NtfyLogbackReattachListener implements LoggerContextListener {

  private final LogbackAlertAppender appender;

  NtfyLogbackReattachListener(LogbackAlertAppender appender) {
    this.appender = appender;
  }

  /** True so Logback preserves this listener (and re-fires it) across a {@code context.reset()}. */
  @Override
  public boolean isResetResistant() {
    return true;
  }

  @Override
  public void onStart(LoggerContext context) {
    reattachIfMissing(context);
  }

  @Override
  public void onReset(LoggerContext context) {
    reattachIfMissing(context);
  }

  @Override
  public void onStop(LoggerContext context) {
    // no-op
  }

  @Override
  public void onLevelChange(Logger logger, Level level) {
    // no-op
  }

  /**
   * Re-attaches the ntfy appender to the root logger when the reset stripped it off, restarting it
   * first if the reset (or a prior {@code stop()}) left it stopped. Idempotent: when the appender is
   * already present under its name, this is a no-op, so it never double-attaches.
   */
  private void reattachIfMissing(LoggerContext context) {
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    if (root.getAppender(appender.getName()) == null) {
      if (!appender.isStarted()) {
        appender.start();
      }
      root.addAppender(appender);
    }
  }
}
