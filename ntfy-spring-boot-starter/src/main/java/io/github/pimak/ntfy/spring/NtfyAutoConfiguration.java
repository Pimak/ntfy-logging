package io.github.pimak.ntfy.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.logback.LogbackAlertAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that turns {@code ntfy.*} properties into a live ntfy integration for a Spring
 * Boot application. It performs two independent jobs:
 *
 * <ul>
 *   <li>Installs (idempotently) a {@link LogbackAlertAppender} named {@code "ntfy-auto"} on the root
 *       Logback logger so ERROR-level events are published as ntfy notifications. This runs once all
 *       singletons are ready, so the Spring-bound configuration always wins over any appender the
 *       ntfy-logback {@code Configurator} SPI may have pre-installed from env/sysprops.
 *   <li>Exposes an injectable {@link NtfyClient} bean for sending ad-hoc notifications from
 *       application code.
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(NtfyProperties.class)
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
public class NtfyAutoConfiguration implements DisposableBean {

  /** Name of the appender this starter manages on the root logger. */
  static final String APPENDER_NAME = "ntfy-auto";

  private static final org.slf4j.Logger log =
      LoggerFactory.getLogger(NtfyAutoConfiguration.class);

  /** Held so it can be stopped and detached cleanly on context shutdown. */
  private volatile LogbackAlertAppender installedAppender;

  /**
   * Installs (or reinstalls) the {@code "ntfy-auto"} appender on the root logger once the context is
   * fully initialized. Registered as a bean so it participates in the container lifecycle and this
   * configuration's {@link #destroy()} runs on shutdown.
   */
  @Bean
  @ConditionalOnProperty(prefix = "ntfy", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  SmartInitializingSingleton ntfyAppenderInstaller(NtfyProperties properties) {
    return () -> installAppender(properties);
  }

  private void installAppender(NtfyProperties p) {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (!(factory instanceof LoggerContext lc)) {
      log.warn("ntfy: SLF4J backend is not Logback ({}); skipping appender installation.",
          factory.getClass().getName());
      return;
    }

    if (!p.isEnabled() || isBlank(p.getUrl()) || isBlank(p.getTopic())) {
      log.info("ntfy: appender not installed (enabled={}, url set={}, topic set={}).",
          p.isEnabled(), !isBlank(p.getUrl()), !isBlank(p.getTopic()));
      return;
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
    appender.setClickUrl(p.getClickUrl());
    appender.setActions(p.getActions());
    appender.setExcludedLoggers(p.getExcludedLoggers());
    appender.setEnabled(p.isEnabled());
    appender.setAsync(p.isAsync());
    appender.setAsyncQueueCapacity(p.getAsyncQueueCapacity());
    appender.start();
    root.addAppender(appender);
    this.installedAppender = appender;
    log.info("ntfy: installed appender '{}' on root logger.", APPENDER_NAME);
  }

  /**
   * Injectable client for sending ad-hoc ntfy notifications from application code. Built directly
   * from {@link NtfyProperties} (durations passed as {@link java.time.Duration} to the core builder).
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "ntfy", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  NtfyClient ntfyClient(NtfyProperties p) {
    NtfyConfig config = NtfyConfig.builder()
        .url(p.getUrl())
        .topic(p.getTopic())
        .token(p.getToken())
        .username(p.getUsername())
        .password(p.getPassword())
        .title(p.getTitle())
        .appName(p.getAppName())
        .maxStackFrames(p.getMaxStackFrames())
        .connectTimeout(p.getConnectTimeout())
        .requestTimeout(p.getRequestTimeout())
        .maxAlertsPerWindow(p.getMaxAlertsPerWindow())
        .suppressionWindow(p.getSuppressionWindow())
        .errorPriority(p.getErrorPriority())
        .digestPriority(p.getDigestPriority())
        .errorTags(p.getErrorTags())
        .digestTags(p.getDigestTags())
        .clickUrl(p.getClickUrl())
        .actionsHeader(p.getActions())
        .excludedLoggers(p.getExcludedLoggers())
        .enabled(p.isEnabled())
        .asyncEnabled(p.isAsync())
        .asyncQueueCapacity(p.getAsyncQueueCapacity())
        .build();
    return new NtfyClient(config);
  }

  @Override
  public void destroy() {
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

  private static String millis(java.time.Duration d) {
    return d == null ? null : String.valueOf(d.toMillis());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
