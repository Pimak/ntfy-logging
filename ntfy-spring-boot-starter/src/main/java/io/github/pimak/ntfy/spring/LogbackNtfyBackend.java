package io.github.pimak.ntfy.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.pimak.ntfy.core.PipelineCounters;
import io.github.pimak.ntfy.logback.LogbackAlertAppender;
import java.time.Duration;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Installs the ntfy appender on Logback — the starter's original and default backend.
 *
 * <p>Every {@code ch.qos.logback} and {@link LogbackAlertAppender} reference in this starter lives
 * in this class, so a Log4j2-only application never loads it (see {@link NtfyBackend}).
 */
final class LogbackNtfyBackend implements NtfyBackend {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(LogbackNtfyBackend.class);

  /** Held so it can be stopped and detached cleanly on context shutdown. */
  private volatile LogbackAlertAppender installedAppender;

  @Override
  public String displayName() {
    return "Logback";
  }

  @Override
  public boolean isActiveBackend() {
    // Classpath presence is not enough — logback-classic can be present while SLF4J is bound to a
    // different provider. The bound ILoggerFactory is the only authority on who receives events.
    return LoggerFactory.getILoggerFactory() instanceof LoggerContext;
  }

  @Override
  public boolean install(NtfyProperties p) {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (!(factory instanceof LoggerContext lc)) {
      log.warn("ntfy: SLF4J backend is not Logback ({}); skipping appender installation.",
          factory.getClass().getName());
      return false;
    }

    Logger root = lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    // Idempotent replace: an appender named "ntfy-auto" may already exist (e.g. installed by the
    // ntfy-logback Configurator SPI from env/sysprops before the Spring context came up). Stop and
    // detach it so Spring-bound values win and there is never a duplicate.
    var existing = root.getAppender(APPENDER_NAME);
    if (existing != null) {
      existing.stop();
      root.detachAppender(existing);
    }

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(lc);
    appender.setName(APPENDER_NAME);
    appender.setUrl(p.getUrl());
    appender.setTopic(p.getTopic());
    appender.setToken(p.getToken());
    appender.setUsername(p.getUsername());
    appender.setPassword(p.getPassword());
    appender.setTitle(p.getTitle());
    appender.setAppName(p.getAppName());
    appender.setMaxStackFrames(p.getMaxStackFrames());
    appender.setConnectTimeout(millis(p.getConnectTimeout()));
    appender.setRequestTimeout(millis(p.getRequestTimeout()));
    appender.setMaxAlertsPerWindow(p.getMaxAlertsPerWindow());
    appender.setSuppressionWindow(millis(p.getSuppressionWindow()));
    appender.setErrorPriority(p.getErrorPriority());
    appender.setDigestPriority(p.getDigestPriority());
    appender.setErrorTags(p.getErrorTags());
    appender.setDigestTags(p.getDigestTags());
    appender.setWarnTopic(p.getWarnTopic());
    appender.setWarnPriority(p.getWarnPriority());
    appender.setWarnTags(p.getWarnTags());
    appender.setClickUrl(p.getClickUrl());
    appender.setActions(p.getActions());
    // String.valueOf on a boolean the framework already parsed: these two surfaces bind the key
    // themselves with converters that understand yes/on/1, and the appender setter takes the raw
    // spelling so the XML surfaces get one shared reading. "true"/"false" round-trips through it.
    appender.setCache(String.valueOf(p.isCache()));
    appender.setFirebase(String.valueOf(p.isFirebase()));
    appender.setExcludedExceptionTypes(p.getExcludedExceptionTypes());
    appender.setLocale(p.getLocale());
    appender.setExcludedLoggers(p.getExcludedLoggers());
    appender.setIncludeMdcKeys(p.getIncludeMdcKeys());
    appender.setEnabled(p.isEnabled());
    appender.setAsync(p.isAsync());
    appender.setAsyncQueueCapacity(p.getAsyncQueueCapacity());
    appender.setRequireHttpsForCredentials(p.isRequireHttpsForCredentials());
    appender.setStartupPing(p.getStartupPing());
    appender.setStartupPingFailFast(p.isStartupPingFailFast());
    appender.setStartupPingWarn(p.getStartupPingWarn());
    appender.setStartupPingNotifyFailures(p.isStartupPingNotifyFailures());
    appender.start();
    if (!appender.isStarted()) {
      // The engine declined to activate and has already said why through the StatusManager (an
      // invalid endpoint, or credentials over plain http:// under the default strict transport
      // mode). Attaching anyway would wire a stopped appender to the root logger, which Logback
      // answers with a status warning, and would leave destroy() tearing down a non-installation.
      return false;
    }
    root.addAppender(appender);
    this.installedAppender = appender;
    return true;
  }

  @Override
  public PipelineCounters counters() {
    LogbackAlertAppender appender = this.installedAppender;
    return appender == null ? null : appender.getCounters();
  }

  @Override
  public void uninstall() {
    LogbackAlertAppender appender = this.installedAppender;
    if (appender == null) {
      return;
    }
    this.installedAppender = null;
    appender.stop();
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext lc) {
      lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
    }
  }

  /** The Logback appender's duration setters are String-typed, so bind them as bare millis. */
  private static String millis(Duration d) {
    return d == null ? null : String.valueOf(d.toMillis());
  }
}
