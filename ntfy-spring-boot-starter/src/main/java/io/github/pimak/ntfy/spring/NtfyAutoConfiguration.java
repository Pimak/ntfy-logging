package io.github.pimak.ntfy.spring;

import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PipelineCounters;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Auto-configuration that turns {@code ntfy.*} properties into a live ntfy integration for a Spring
 * Boot application. It performs two independent jobs:
 *
 * <ul>
 *   <li>Installs (idempotently) an appender named {@code "ntfy-auto"} on the root logger so
 *       ERROR-level events are published as ntfy notifications. This runs once all singletons are
 *       ready, so the Spring-bound configuration always wins over any appender a zero-code
 *       auto-install may have attached earlier from env/sysprops.
 *   <li>Exposes an injectable {@link NtfyClient} bean for sending ad-hoc notifications from
 *       application code.
 * </ul>
 *
 * <p><strong>Backend-agnostic.</strong> This class names no logging-framework type. Each supported
 * backend is a {@link NtfyBackend} contributed by a nested configuration guarded on its own
 * classes, and the installer picks the first one that reports itself bound. That is what lets a
 * {@code spring-boot-starter-log4j2} application get alerting; before, a class-level
 * {@code @ConditionalOnClass} on Logback skipped this entire auto-configuration on such a
 * classpath, taking the {@link NtfyClient} bean and the Micrometer meters down with it.
 */
@AutoConfiguration
@EnableConfigurationProperties(NtfyProperties.class)
public class NtfyAutoConfiguration implements DisposableBean {

  /** Name of the appender this starter manages on the root logger. */
  static final String APPENDER_NAME = NtfyBackend.APPENDER_NAME;

  private static final org.slf4j.Logger log =
      LoggerFactory.getLogger(NtfyAutoConfiguration.class);

  /** The backend that actually installed, held so it can be torn down on context shutdown. */
  private volatile NtfyBackend installedBackend;

  /**
   * Installs (or reinstalls) the {@code "ntfy-auto"} appender on the root logger once the context is
   * fully initialized. Registered as a bean so it participates in the container lifecycle and this
   * configuration's {@link #destroy()} runs on shutdown.
   */
  @Bean
  @ConditionalOnProperty(prefix = "ntfy", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  SmartInitializingSingleton ntfyAppenderInstaller(NtfyProperties properties,
      ObjectProvider<NtfyBackend> backends) {
    return () -> installAppender(properties, backends);
  }

  private void installAppender(NtfyProperties p, ObjectProvider<NtfyBackend> backends) {
    if (!p.isEnabled() || isBlank(p.getUrl()) || isBlank(p.getTopic())) {
      log.info("ntfy: appender not installed (enabled={}, url set={}, topic set={}).",
          p.isEnabled(), !isBlank(p.getUrl()), !isBlank(p.getTopic()));
      return;
    }

    // orderedStream() honours the @Order on the nested @Bean methods, so the selection is
    // deterministic when a bridge puts both backends on the classpath: Logback is consulted first
    // and keeps its long-standing behaviour, Log4j2 only picks up what Logback does not claim.
    NtfyBackend backend = backends.orderedStream()
        .filter(NtfyBackend::isActiveBackend)
        .findFirst()
        .orElse(null);
    if (backend == null) {
      log.warn("ntfy: no supported logging backend is bound (looked for Logback and Log4j2); "
          + "skipping appender installation. The NtfyClient bean is still available for "
          + "manual notifications.");
      return;
    }

    if (!backend.install(p)) {
      // The backend declined and has already explained why on its own status channel. Leaving
      // installedBackend null keeps destroy() from tearing down an installation that never
      // happened, and keeps the meters reading 0 rather than pointing at a dead appender.
      log.warn("ntfy: the {} backend did not install the appender; see the logging framework's "
          + "own status output for the reason.", backend.displayName());
      return;
    }
    this.installedBackend = backend;
    log.info("ntfy: installed appender '{}' on the root {} logger.",
        APPENDER_NAME, backend.displayName());
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
        .warnTopic(p.getWarnTopic())
        .warnPriority(p.getWarnPriority())
        .warnTags(p.getWarnTags())
        .clickUrl(p.getClickUrl())
        .actionsHeader(p.getActions())
        .locale(p.getLocale())
        .excludedLoggers(p.getExcludedLoggers())
        .excludedExceptionTypesCsv(p.getExcludedExceptionTypes())
        .cache(p.isCache())
        .firebase(p.isFirebase())
        .includeMdcKeysCsv(p.getIncludeMdcKeys())
        .enabled(p.isEnabled())
        .asyncEnabled(p.isAsync())
        .asyncQueueCapacity(p.getAsyncQueueCapacity())
        .requireHttpsForCredentials(p.isRequireHttpsForCredentials())
        .build();
    return new NtfyClient(config);
  }

  /**
   * The currently-installed appender's pipeline counters, or {@code null} when none is active. Read
   * lazily by the Micrometer binding at scrape time so meters always reflect the current appender
   * (and null-safe to 0 before install / after shutdown). The backing field is {@code volatile}, so
   * this read is safe from meter-scrape threads.
   *
   * <p>Returning {@link PipelineCounters} rather than an appender is what keeps this method — and
   * therefore this class — free of any logging-framework type.
   */
  PipelineCounters installedCounters() {
    NtfyBackend backend = this.installedBackend;
    return backend == null ? null : backend.counters();
  }

  @Override
  public void destroy() {
    NtfyBackend backend = this.installedBackend;
    if (backend == null) {
      return;
    }
    this.installedBackend = null;
    backend.uninstall();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * Contributes the Logback backend when logback-classic is on the classpath. Ordered first so an
   * application with both backends present keeps the behaviour it had before Log4j2 was supported.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
  static class NtfyLogbackBackendConfiguration {

    @Bean
    @Order(0)
    NtfyBackend logbackNtfyBackend() {
      return new LogbackNtfyBackend();
    }
  }

  /**
   * Contributes the Log4j2 backend when log4j-core is on the classpath — the
   * {@code spring-boot-starter-log4j2} case.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.apache.logging.log4j.core.LoggerContext")
  static class NtfyLog4j2BackendConfiguration {

    @Bean
    @Order(10)
    NtfyBackend log4j2NtfyBackend() {
      return new Log4j2NtfyBackend();
    }
  }

  /**
   * Micrometer binding for the ntfy pipeline counters, isolated in a nested {@code
   * @ConditionalOnClass(MeterRegistry)} configuration so no Micrometer type ever appears in the
   * outer class's introspected signatures. When micrometer-core is absent, this whole class is
   * skipped and never loaded; when present, the binder bean is created only if a {@code
   * MeterRegistry} bean exists.
   *
   * <p>The {@code MeterRegistry} appears only as a {@code @Bean} method parameter of THIS nested
   * class (loaded lazily, after its own {@code @ConditionalOnClass} has already passed) and inside
   * {@link NtfyMetricsBinder} — never in {@link NtfyAutoConfiguration} itself.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class NtfyMicrometerConfiguration {

    @Bean
    @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
    @ConditionalOnProperty(prefix = "ntfy", name = "enabled", havingValue = "true",
        matchIfMissing = true)
    NtfyMetricsBinder ntfyMetricsBinder(io.micrometer.core.instrument.MeterRegistry registry,
        NtfyAutoConfiguration autoConfiguration) {
      // Lazy supplier: meters resolve the current counters at scrape time (null-safe to 0), so they
      // survive a re-install and never depend on installer-vs-binder ordering.
      return new NtfyMetricsBinder(registry, autoConfiguration::installedCounters);
    }
  }
}
