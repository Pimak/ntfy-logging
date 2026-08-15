package io.github.pimak.ntfy.micronaut;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.github.pimak.ntfy.logback.LogbackAlertAppender;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Installs the ntfy appender named {@code "ntfy-auto"} on the root Logback logger once the
 * Micronaut context has started, and detaches it again on shutdown.
 *
 * <p>This is the only Logback-aware type in the module. It is a port of the Spring starter's
 * {@code LogbackNtfyBackend}, minus the backend abstraction: Micronaut ships Logback (that is what
 * {@code micronaut-logging} binds by default) and Log4j2-on-Micronaut is rare enough that a third
 * copy of the starter's two-backend indirection would cost more than it buys. The consequence is
 * spelled out in {@link NtfyClientFactory}: only the appender install is Logback-specific, never
 * the client bean.
 *
 * <p>Installation runs on {@link StartupEvent} rather than in a constructor so that whatever the
 * zero-code {@code ntfy-logback} Configurator SPI may have attached from environment variables
 * before the context existed is replaced by the Micronaut-bound configuration, exactly once, after
 * the container is up.
 *
 * <p>The {@link Requires} guard keeps this bean out of a context whose classpath has no Logback at
 * all — the bean type could not even be loaded there. Logback being on the classpath is still not
 * proof that it is the bound backend, so {@link #install(ILoggerFactory)} re-checks the live logger
 * factory and warns rather than throwing.
 */
@Singleton
@Requires(classes = LoggerContext.class)
public class NtfyLogbackInstaller implements ApplicationEventListener<StartupEvent> {

  /**
   * Name of the appender this module manages on the root logger. Identical across every ntfy
   * integration, so an install from one path idempotently replaces an install from another.
   */
  public static final String APPENDER_NAME = "ntfy-auto";

  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(NtfyLogbackInstaller.class);

  private final NtfyConfiguration configuration;

  /** Held so it can be stopped and detached cleanly on context shutdown. Never logged. */
  private volatile LogbackAlertAppender installedAppender;

  /**
   * Creates the installer.
   *
   * @param configuration the bound {@code ntfy.*} configuration
   */
  public NtfyLogbackInstaller(NtfyConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * Installs the appender once the application context has started.
   *
   * @param event the Micronaut startup event (unused; the trigger is what matters)
   */
  @Override
  public void onApplicationEvent(StartupEvent event) {
    install(LoggerFactory.getILoggerFactory());
  }

  /**
   * Installs (or idempotently reinstalls) the {@code "ntfy-auto"} appender on the root logger of
   * {@code factory}.
   *
   * <p>Package-private and taking the factory as a parameter purely so a test can hand in a
   * non-Logback {@link ILoggerFactory} and assert the warn-and-skip path; production code always
   * passes {@link LoggerFactory#getILoggerFactory()}.
   *
   * @param factory the SLF4J logger factory the application is actually bound to
   * @return {@code true} when a started appender was attached, {@code false} when nothing was
   *     installed (the reason has already been logged, or reported by the engine on Logback's own
   *     status channel)
   */
  boolean install(ILoggerFactory factory) {
    if (!configuration.isEnabled()
        || isBlank(configuration.getUrl())
        || isBlank(configuration.getTopic())) {
      // Not an error: this is the state of every application that has the module on the classpath
      // but has not configured a topic yet. Only the flags are logged — never a credential.
      LOG.info("ntfy: appender not installed (enabled={}, url set={}, topic set={}).",
          configuration.isEnabled(), !isBlank(configuration.getUrl()),
          !isBlank(configuration.getTopic()));
      return false;
    }

    // Classpath presence is not enough — logback-classic can be present while SLF4J is bound to a
    // different provider (a bridge, or a test harness). The bound ILoggerFactory is the only
    // authority on who receives events. Warn and skip; the NtfyClient bean stays usable.
    if (!(factory instanceof LoggerContext lc)) {
      LOG.warn("ntfy: SLF4J backend is not Logback ({}); skipping appender installation. "
              + "The NtfyClient bean is still available for manual notifications.",
          factory == null ? "none" : factory.getClass().getName());
      return false;
    }

    ch.qos.logback.classic.Logger root = lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    // Idempotent replace: an appender named "ntfy-auto" may already exist (e.g. installed by the
    // ntfy-logback Configurator SPI from env/sysprops before the Micronaut context came up). Stop
    // and detach it so the Micronaut-bound values win and there is never a duplicate.
    Appender<ILoggingEvent> existing = root.getAppender(APPENDER_NAME);
    if (existing != null) {
      existing.stop();
      root.detachAppender(existing);
    }

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(lc);
    appender.setName(APPENDER_NAME);
    appender.setUrl(configuration.getUrl());
    appender.setTopic(configuration.getTopic());
    appender.setToken(configuration.getToken());
    appender.setUsername(configuration.getUsername());
    appender.setPassword(configuration.getPassword());
    appender.setTitle(configuration.getTitle());
    appender.setAppName(configuration.getAppName());
    appender.setMaxStackFrames(configuration.getMaxStackFrames());
    appender.setConnectTimeout(millis(configuration.getConnectTimeout()));
    appender.setRequestTimeout(millis(configuration.getRequestTimeout()));
    appender.setMaxAlertsPerWindow(configuration.getMaxAlertsPerWindow());
    appender.setSuppressionWindow(millis(configuration.getSuppressionWindow()));
    appender.setErrorPriority(configuration.getErrorPriority());
    appender.setDigestPriority(configuration.getDigestPriority());
    appender.setErrorTags(configuration.getErrorTags());
    appender.setDigestTags(configuration.getDigestTags());
    appender.setClickUrl(configuration.getClickUrl());
    appender.setActions(configuration.getActions());
    appender.setLocale(configuration.getLocale());
    appender.setExcludedLoggers(configuration.getExcludedLoggers());
    appender.setEnabled(configuration.isEnabled());
    appender.setAsync(configuration.isAsync());
    appender.setAsyncQueueCapacity(configuration.getAsyncQueueCapacity());
    appender.setRequireHttpsForCredentials(configuration.isRequireHttpsForCredentials());
    appender.start();
    if (!appender.isStarted()) {
      // The engine declined to activate and has already said why through the StatusManager (an
      // invalid endpoint, or credentials over plain http:// under the default strict transport
      // mode). Attaching anyway would wire a stopped appender to the root logger, which Logback
      // answers with a status warning on every event, and would leave uninstall() tearing down a
      // non-installation.
      LOG.warn("ntfy: the appender did not start; see Logback's own status output for the reason.");
      return false;
    }
    root.addAppender(appender);
    this.installedAppender = appender;
    LOG.info("ntfy: installed appender '{}' on the root Logback logger.", APPENDER_NAME);
    return true;
  }

  /**
   * Stops the installed appender and detaches it from the root logger on context shutdown.
   * Idempotent and safe when nothing was ever installed, so a context that skipped installation
   * shuts down as quietly as one that never had the module.
   */
  @PreDestroy
  public void uninstall() {
    LogbackAlertAppender appender = this.installedAppender;
    if (appender == null) {
      return;
    }
    // Cleared first so a concurrent second shutdown cannot stop/detach the same appender twice.
    this.installedAppender = null;
    appender.stop();
    if (LoggerFactory.getILoggerFactory() instanceof LoggerContext lc) {
      lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** The Logback appender's duration setters are String-typed, so bind them as bare millis. */
  private static String millis(Duration d) {
    return d == null ? null : String.valueOf(d.toMillis());
  }
}
