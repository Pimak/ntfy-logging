package io.github.pimak.ntfy.log4j2;

import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import io.github.pimak.ntfy.core.Diagnostics;
import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * Programmatic one-liner install of the ntfy log4j2 appender from the ambient environment: {@code
 * NtfyLog4j2Installer.install()} early in {@code main} attaches a started {@link
 * NtfyLog4j2Appender} to the root logger at {@link Level#ERROR} when the environment configures
 * ntfy ({@code NTFY_URL}/{@code NTFY_TOPIC}, {@code -Dntfy.*}, or a trusted classpath {@code
 * ntfy.properties} — see {@link Log4j2AmbientConfig} for the classpath-endpoint opt-in guard), and
 * does nothing otherwise.
 *
 * <p>Prefer the declarative {@code <Ntfy .../>} element in {@code log4j2.xml} when you already own
 * the logging configuration; this entry point exists for applications that configure ntfy purely
 * from the environment, and for the Spring Boot starter's log4j2 branch.
 *
 * <p>The install also registers a {@link NtfyLog4j2ReattachListener} so a later {@code
 * Configurator.reconfigure()} (or a watched {@code log4j2.xml} being reloaded) cannot silently drop
 * the appender.
 *
 * <p>The returned appender owns its engine: call {@link #uninstall(LoggerContext,
 * NtfyLog4j2Appender)} on shutdown to flush any pending digest, drain the async queue and release
 * the HTTP client. log4j2's own shutdown hook stops the configuration — and with it this appender —
 * on normal JVM exit.
 */
public final class NtfyLog4j2Installer {

  /** The name the programmatically installed appender is registered under. */
  static final String APPENDER_NAME = "ntfy-auto";

  private NtfyLog4j2Installer() {}

  /**
   * Installs on the current {@code LoggerContext} ({@code LoggerContext.getContext(false)} — the
   * caller's context, without forcing initialization of a new one). See {@link
   * #install(LoggerContext)}.
   *
   * @return the installed appender, or empty when nothing was installed
   */
  public static Optional<NtfyLog4j2Appender> install() {
    return install(LoggerContext.getContext(false));
  }

  /**
   * Resolves the ambient config and, when active and vetted, attaches a started {@link
   * NtfyLog4j2Appender} to {@code context}'s root logger at {@link Level#ERROR}.
   *
   * <p>Empty — with nothing attached — when ntfy is not configured, the classpath-endpoint guard
   * refused, the engine declined to activate (e.g. strict {@code requireHttpsForCredentials}), or
   * resolution/construction failed. Like the JUL installer, this entry point never lets an
   * alerting-setup failure propagate into the caller; every failure is reported on log4j2's
   * StatusLogger instead.
   *
   * @param context the logger context to install into
   * @return the installed appender, or empty when nothing was installed
   */
  public static Optional<NtfyLog4j2Appender> install(LoggerContext context) {
    Objects.requireNonNull(context, "context");
    Diagnostics diagnostics = new Log4j2Diagnostics();
    try {
      Optional<NtfyConfig> resolved = Log4j2AmbientConfig.resolve(diagnostics);
      if (resolved.isEmpty()) {
        return Optional.empty();
      }

      NtfyLog4j2Appender appender = NtfyLog4j2Appender.forConfig(APPENDER_NAME, resolved.get());
      appender.start();
      if (!appender.isStarted()) {
        // The engine refused to activate and already explained why. Attaching a stopped appender
        // would just add an ErrorHandler complaint per logged event on top of that.
        return Optional.empty();
      }

      Configuration configuration = context.getConfiguration();
      configuration.addAppender(appender);
      LoggerConfig root = configuration.getRootLogger();
      // Level.ERROR, no filter: the level floor is enforced twice on purpose — here so log4j2 never
      // even dispatches sub-ERROR events to us, and again inside the appender so a direct
      // append()/other attachment point cannot bypass the documented contract.
      root.addAppender(appender, Level.ERROR, null);
      context.updateLoggers();

      NtfyLog4j2ReattachListener listener = new NtfyLog4j2ReattachListener(appender);
      appender.setReattachListener(listener);
      context.addPropertyChangeListener(listener);

      diagnostics.info(
          "ntfy: installed appender '" + APPENDER_NAME + "' on the log4j2 root logger");
      return Optional.of(appender);
    } catch (RuntimeException e) {
      // Same defense as NtfyJulInstaller: a failure to read ambient config or to touch the logger
      // context degrades to "not installed", never a throw out of a one-liner documented as always
      // safe to call.
      diagnostics.error("ntfy: installer initialization failed", e);
      return Optional.empty();
    }
  }

  /**
   * Reverses {@link #install(LoggerContext)}: deregisters the reconfiguration listener, detaches
   * the appender from the root logger by name, refreshes the loggers and stops the appender (which
   * flushes any pending digest and drains the async queue). Safe to call on an appender that was
   * never installed; safe to call twice.
   *
   * @param context the logger context the appender was installed into
   * @param appender the appender returned by {@code install}
   */
  public static void uninstall(LoggerContext context, NtfyLog4j2Appender appender) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(appender, "appender");

    NtfyLog4j2ReattachListener listener = appender.reattachListener();
    if (listener != null) {
      // Remove the hook FIRST: were the appender detached while the listener is still registered, a
      // reconfiguration racing this teardown would faithfully resurrect it.
      context.removePropertyChangeListener(listener);
      appender.setReattachListener(null);
    }

    context.getConfiguration().getRootLogger().removeAppender(appender.getName());
    context.updateLoggers();
    appender.stop();
  }
}
