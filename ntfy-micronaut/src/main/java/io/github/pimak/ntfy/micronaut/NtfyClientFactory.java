package io.github.pimak.ntfy.micronaut;

import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * Exposes the injectable {@link NtfyClient} bean, built from the bound {@link NtfyConfiguration}.
 *
 * <p><strong>Unconditional by design.</strong> The client is a plain HTTP publisher for ad-hoc
 * notifications from application code; it does not touch a logging framework, so it is contributed
 * whatever SLF4J backend the application binds and even when {@code ntfy.url}/{@code ntfy.topic}
 * are unset (an inactive configuration simply publishes nothing). Only the appender install in
 * {@link NtfyLogbackInstaller} is Logback-specific. The Spring starter originally gated its whole
 * auto-configuration on Logback being present and lost the client bean along with the appender on a
 * Log4j2 classpath; that mistake is not repeated here — no Logback type appears anywhere in this
 * class or in the signatures the container introspects on it.
 */
@Factory
public class NtfyClientFactory {

  /**
   * Builds the singleton {@link NtfyClient}.
   *
   * <p>{@code preDestroy = "close"} hands the client's lifecycle to the container: {@link
   * NtfyClient} is {@link AutoCloseable} and owns an async delivery worker, so the context shutdown
   * must close it or the worker outlives the application.
   *
   * <p>Two builder names deliberately differ from the configuration property names, mirroring the
   * Spring starter: {@code ntfy.actions} maps onto {@link NtfyConfig.Builder#actionsHeader(String)}
   * (the value is a raw ntfy {@code Actions} header, not a parsed action list) and {@code
   * ntfy.async} maps onto {@link NtfyConfig.Builder#asyncEnabled(boolean)}. A third, {@code
   * ntfy.include-mdc-keys}, maps onto {@link NtfyConfig.Builder#includeMdcKeysCsv(String)} — the
   * comma-separated form, since the property binds as a single {@code String}.
   *
   * <p>Every bound property must be forwarded here. This method and {@link NtfyLogbackInstaller}
   * are two independent apply sites for one configuration, so a key wired into only one of them
   * produces a client whose config silently disagrees with the appender's — with nothing failing to
   * announce it. {@code include-mdc-keys} was exactly that omission until it was added here.
   *
   * @param configuration the bound {@code ntfy.*} configuration
   * @return a client bean the container closes on shutdown
   */
  @Singleton
  @Bean(preDestroy = "close")
  public NtfyClient ntfyClient(NtfyConfiguration configuration) {
    NtfyConfig config = NtfyConfig.builder()
        .url(configuration.getUrl())
        .topic(configuration.getTopic())
        .token(configuration.getToken())
        .username(configuration.getUsername())
        .password(configuration.getPassword())
        .title(configuration.getTitle())
        .appName(configuration.getAppName())
        .maxStackFrames(configuration.getMaxStackFrames())
        .connectTimeout(configuration.getConnectTimeout())
        .requestTimeout(configuration.getRequestTimeout())
        .maxAlertsPerWindow(configuration.getMaxAlertsPerWindow())
        .suppressionWindow(configuration.getSuppressionWindow())
        .errorPriority(configuration.getErrorPriority())
        .digestPriority(configuration.getDigestPriority())
        .errorTags(configuration.getErrorTags())
        .digestTags(configuration.getDigestTags())
        .clickUrl(configuration.getClickUrl())
        .actionsHeader(configuration.getActions())
        .locale(configuration.getLocale())
        .excludedLoggers(configuration.getExcludedLoggers())
        .includeMdcKeysCsv(configuration.getIncludeMdcKeys())
        .enabled(configuration.isEnabled())
        .asyncEnabled(configuration.isAsync())
        .asyncQueueCapacity(configuration.getAsyncQueueCapacity())
        .requireHttpsForCredentials(configuration.isRequireHttpsForCredentials())
        .build();
    return new NtfyClient(config);
  }
}
