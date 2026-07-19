package io.github.pimak.ntfy.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.spi.ContextAwareBase;

import io.github.pimak.ntfy.core.ConfigLoader;
import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * A Logback {@link Configurator} SPI implementation that auto-installs the ntfy appender on the root
 * logger with zero XML — activated only when the ambient environment actually configures ntfy.
 * Registered via {@code META-INF/services/ch.qos.logback.classic.spi.Configurator}; Logback calls
 * {@link #setContext} then {@link #configure(LoggerContext)} during default configuration (when no
 * {@code logback.xml}/{@code logback-test.xml} is present, or as one link in the configurator
 * chain).
 *
 * <p>Config is loaded via {@link ConfigLoader#load()} (system properties &gt; env &gt; classpath
 * {@code ntfy.properties} &gt; defaults). If the resolved config is not {@link
 * NtfyConfig#isActive() active}, this configurator does nothing beyond an info status and hands off
 * to the next configurator; otherwise it builds a {@link LogbackAlertAppender} (injecting the config
 * directly), attaches it to the root logger, and registers a reset-resistant {@link
 * NtfyLogbackReattachListener} so a later application {@code JoranConfigurator} run cannot silently
 * detach it.
 */
public class NtfyLogbackConfigurator extends ContextAwareBase implements Configurator {

  /** The name the auto-installed appender is registered under on the root logger. */
  static final String APPENDER_NAME = "ntfy-auto";

  @Override
  public ExecutionStatus configure(LoggerContext loggerContext) {
    NtfyConfig cfg = ConfigLoader.load();
    if (!cfg.isActive()) {
      addInfo("ntfy: not configured, auto-install inactive");
      return ExecutionStatus.INVOKE_NEXT_IF_ANY;
    }

    if (cfg.isEndpointFromClasspathFile()) {
      // WARN (not info): any jar on the classpath can carry a ntfy.properties, so activation from
      // classpath content alone must be visible by default — Logback prints WARN-level status on
      // the console — naming the destination so an unexpected endpoint is caught immediately.
      // Same userinfo-strip as the engine's ACTIVE line: never echo embedded credentials.
      addWarn(
          "ntfy: endpoint URL comes from a classpath ntfy.properties (no NTFY_URL env var or "
              + "ntfy.url system property set) — alerts will be published to '"
              + cfg.getUrl().replaceFirst("//[^/]*@", "//")
              + "'; make sure that file is one you trust");
    }

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(loggerContext);
    appender.setName(APPENDER_NAME);
    appender.setConfig(cfg);
    appender.start();

    Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);

    loggerContext.addListener(new NtfyLogbackReattachListener(appender));

    addInfo("ntfy: auto-installed appender '" + APPENDER_NAME + "' on the root logger");
    // Neutral hand-off: never suppress a following configurator (e.g. a real logback.xml), so the
    // application's own logging config still applies on top of the auto-installed ntfy appender.
    return ExecutionStatus.INVOKE_NEXT_IF_ANY;
  }
}
