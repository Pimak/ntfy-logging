package io.github.pimak.ntfy.spring;

import io.github.pimak.ntfy.core.PipelineCounters;
import io.github.pimak.ntfy.log4j2.NtfyLog4j2Appender;
import java.time.Duration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.LoggerFactory;

/**
 * Installs the ntfy appender on Log4j2, which is what a Spring Boot application built on {@code
 * spring-boot-starter-log4j2} runs.
 *
 * <p>Every {@code org.apache.logging.log4j} and {@link NtfyLog4j2Appender} reference in this
 * starter lives in this class, so a Logback application never loads it (see {@link NtfyBackend}).
 *
 * <p>Unlike the adapter's own {@code NtfyLog4j2Installer}, this backend binds Spring-managed
 * properties rather than the ambient environment, and — exactly like the Logback path here — it
 * performs a plain attach with no reconfiguration-survival listener. Spring owns the appender's
 * lifecycle through the container, and a mid-life {@code log4j2.xml} reload in a Spring
 * application would already be re-running the container's own configuration.
 */
final class Log4j2NtfyBackend implements NtfyBackend {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(Log4j2NtfyBackend.class);

  /** Held so it can be stopped and detached cleanly on context shutdown. */
  private volatile NtfyLog4j2Appender installedAppender;

  @Override
  public String displayName() {
    return "Log4j2";
  }

  @Override
  public boolean isActiveBackend() {
    // log4j-api can be present with a different (or no) implementation behind it — log4j-to-slf4j
    // routes the API to SLF4J and yields no core LoggerContext at all. Only a core LoggerContext
    // means log4j-core is the live implementation and has a Configuration to attach to.
    return LogManager.getContext(false) instanceof LoggerContext;
  }

  @Override
  public boolean install(NtfyProperties p) {
    if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
      log.warn("ntfy: Log4j2 is on the classpath but log4j-core is not the active implementation; "
          + "skipping appender installation.");
      return false;
    }

    Configuration configuration = context.getConfiguration();
    LoggerConfig root = configuration.getRootLogger();

    // Idempotent replace, mirroring the Logback path: an "ntfy-auto" appender may already be
    // attached (a <Ntfy> element in log4j2.xml, or a NtfyLog4j2Installer.install() call in main).
    // Detach and stop it so Spring-bound values win and there is never a duplicate.
    var existing = root.getAppenders().get(APPENDER_NAME);
    if (existing != null) {
      root.removeAppender(APPENDER_NAME);
      existing.stop();
    }

    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.newBuilder()
            .setName(APPENDER_NAME)
            .setUrl(p.getUrl())
            .setTopic(p.getTopic())
            .setToken(p.getToken())
            .setUsername(p.getUsername())
            .setPassword(p.getPassword())
            .setTitle(p.getTitle())
            .setAppName(p.getAppName())
            .setMaxStackFrames(String.valueOf(p.getMaxStackFrames()))
            .setConnectTimeout(millis(p.getConnectTimeout()))
            .setRequestTimeout(millis(p.getRequestTimeout()))
            .setMaxAlertsPerWindow(String.valueOf(p.getMaxAlertsPerWindow()))
            .setSuppressionWindow(millis(p.getSuppressionWindow()))
            .setErrorPriority(p.getErrorPriority())
            .setDigestPriority(p.getDigestPriority())
            .setErrorTags(p.getErrorTags())
            .setDigestTags(p.getDigestTags())
            .setWarnTopic(p.getWarnTopic())
            .setWarnPriority(p.getWarnPriority())
            .setWarnTags(p.getWarnTags())
            .setClickUrl(p.getClickUrl())
            .setActions(p.getActions())
            .setLocale(p.getLocale())
            .setExcludedLoggers(p.getExcludedLoggers())
            .setIncludeMdcKeys(p.getIncludeMdcKeys())
            .setEnabled(String.valueOf(p.isEnabled()))
            .setAsync(String.valueOf(p.isAsync()))
            .setAsyncQueueCapacity(String.valueOf(p.getAsyncQueueCapacity()))
            .setRequireHttpsForCredentials(String.valueOf(p.isRequireHttpsForCredentials()))
            .setStartupPing(p.getStartupPing())
            .setStartupPingFailFast(String.valueOf(p.isStartupPingFailFast()))
            .build();
    appender.start();
    if (!appender.isStarted()) {
      // The engine declined to activate and has already said why on the StatusLogger (an invalid
      // endpoint, or credentials over plain http:// under the default strict transport mode).
      // Log4j2 reports an error for EVERY event dispatched to a stopped appender, so attaching one
      // would turn a quiet misconfiguration into per-event noise.
      return false;
    }

    configuration.addAppender(appender);
    // No filter, and the level from the started appender: the same root-logger contract every other
    // adapter establishes, and the same single source of truth NtfyLog4j2Installer reads. ERROR
    // unless the engine actually activated a warn route — on log4j2 the attach level is a second
    // floor, so an appender attached at ERROR would never be handed a warning to alert on.
    root.addAppender(appender, appender.attachLevel(), null);
    context.updateLoggers();
    this.installedAppender = appender;
    return true;
  }

  @Override
  public PipelineCounters counters() {
    NtfyLog4j2Appender appender = this.installedAppender;
    return appender == null ? null : appender.getCounters();
  }

  @Override
  public void uninstall() {
    NtfyLog4j2Appender appender = this.installedAppender;
    if (appender == null) {
      return;
    }
    this.installedAppender = null;
    if (LogManager.getContext(false) instanceof LoggerContext context) {
      context.getConfiguration().getRootLogger().removeAppender(APPENDER_NAME);
      context.updateLoggers();
    }
    appender.stop();
  }

  /** The Log4j2 appender's attributes are all string-valued, so bind durations as bare millis. */
  private static String millis(Duration d) {
    return d == null ? null : String.valueOf(d.toMillis());
  }
}
