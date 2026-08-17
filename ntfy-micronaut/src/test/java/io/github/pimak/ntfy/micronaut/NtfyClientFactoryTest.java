package io.github.pimak.ntfy.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requirements;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.BeanDefinition;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@link NtfyClient} bean must exist in every context that has this module on the classpath —
 * configured or not, enabled or not, Logback-bound or not. Only appender installation is allowed to
 * be conditional.
 */
class NtfyClientFactoryTest {

  @Test
  void blankUrlAndTopic_ntfyClientBeanIsStillInjectable() {
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(context.getBean(NtfyClient.class)).as("NtfyClient bean").isNotNull();
    }
  }

  @Test
  void disabled_ntfyClientBeanIsStillInjectable() {
    try (ApplicationContext context =
        ApplicationContext.run(Map.of("ntfy.enabled", false, "ntfy.topic", "alerts"))) {
      assertThat(context.getBean(NtfyClient.class)).as("NtfyClient bean").isNotNull();
    }
  }

  /**
   * Every bound {@code ntfy.*} key must reach the client bean's config, not just the ones the
   * appender install happens to use. {@code include-mdc-keys} is the regression case: the factory
   * forwarded twenty-odd properties and silently skipped this one, so an operator who configured an
   * allow-list got it honoured by the appender ({@link NtfyLogbackInstaller}) and dropped by the
   * injectable client — two apply sites for one configuration, disagreeing with nothing to say so.
   *
   * <p>{@link NtfyClient} keeps its {@link NtfyConfig} private and exposes no accessor, so the field
   * is read reflectively rather than widening the production API for a test. This mirrors the Spring
   * starter's {@code includeMdcKeys_reachesTheNtfyClientBean}, which is what this module lacked.
   */
  @Test
  void everyConfiguredKeyReachesTheClientConfig_includingIncludeMdcKeys() throws Exception {
    Map<String, Object> properties = Map.of(
        "ntfy.url", "https://127.0.0.1:1",
        "ntfy.topic", "alerts",
        "ntfy.include-mdc-keys", "requestId,tenant",
        "ntfy.excluded-loggers", "com.noisy",
        "ntfy.enabled", false);

    try (ApplicationContext context = ApplicationContext.run(properties)) {
      NtfyConfig config = configOf(context.getBean(NtfyClient.class));

      assertThat(config.getIncludeMdcKeys())
          .as("MDC allow-list on the NtfyClient bean's config")
          .containsExactly("requestId", "tenant");
      // A second forwarded key, so a factory that dropped everything could not pass this test by
      // accident of the allow-list defaulting to something non-empty.
      assertThat(config.getExcludedLoggerPrefixes()).contains("com.noisy");
    }
  }

  /**
   * The same invariant, for the level-routing keys added after {@code include-mdc-keys}: they are
   * bound on {@link NtfyConfiguration} and applied by {@link NtfyLogbackInstaller}, so the factory
   * must forward them too or the injectable client would publish to the error topic while the
   * appender routes warnings elsewhere.
   */
  @Test
  void everyConfiguredKeyReachesTheClientConfig_includingTheWarnRoute() throws Exception {
    Map<String, Object> properties = Map.of(
        "ntfy.url", "https://127.0.0.1:1",
        "ntfy.topic", "alerts",
        "ntfy.warn-topic", "alerts-warn",
        "ntfy.warn-priority", "low",
        "ntfy.warn-tags", "eyes",
        "ntfy.enabled", false);

    try (ApplicationContext context = ApplicationContext.run(properties)) {
      NtfyConfig config = configOf(context.getBean(NtfyClient.class));

      assertThat(config.getWarnTopic()).isEqualTo("alerts-warn");
      assertThat(config.isWarnRoutingEnabled()).isTrue();
      assertThat(config.getWarnPriority()).isEqualTo("low");
      assertThat(config.getWarnTags()).isEqualTo("eyes");
    }
  }

  /** Unset means no warn route on the client's config either — alerting stays ERROR-only. */
  @Test
  void noWarnTopicConfigured_clientConfigHasNoWarnRoute() throws Exception {
    try (ApplicationContext context = ApplicationContext.run()) {
      NtfyConfig config = configOf(context.getBean(NtfyClient.class));

      assertThat(config.getWarnTopic()).isNull();
      assertThat(config.isWarnRoutingEnabled()).isFalse();
    }
  }

  /** Unset means an empty allow-list, never null — the client must not consult any MDC key. */
  @Test
  void noIncludeMdcKeysConfigured_clientConfigCarriesAnEmptyAllowList() throws Exception {
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(configOf(context.getBean(NtfyClient.class)).getIncludeMdcKeys()).isEmpty();
    }
  }

  private static NtfyConfig configOf(NtfyClient client) throws Exception {
    Field configField = NtfyClient.class.getDeclaredField("config");
    configField.setAccessible(true);
    return (NtfyConfig) configField.get(client);
  }

  @Test
  void ntfyClientBeanDefinition_carriesNoConditionAtAll() {
    // Logback cannot be taken off a running JVM's classpath, so "the client survives a non-Logback
    // application" is asserted structurally instead: the @Factory method declares no @Requires of
    // any kind, therefore nothing about the logging backend can gate it. The companion runtime
    // assertion — a non-Logback SLF4J binding skips installation without throwing — lives in
    // NtfyLogbackInstallerTest.
    try (ApplicationContext context = ApplicationContext.run()) {
      BeanDefinition<NtfyClient> definition = context.getBeanDefinition(NtfyClient.class);

      assertThat(definition.getAnnotationMetadata().hasAnnotation(Requires.class))
          .as("@Requires on the NtfyClient bean definition").isFalse();
      assertThat(definition.getAnnotationMetadata().hasAnnotation(Requirements.class))
          .as("@Requirements on the NtfyClient bean definition").isFalse();
    }
  }

  @Test
  void logbackInstallerBeanDefinition_isTheOnlyOneGatedOnLogback() {
    // The mirror image of the assertion above: the Logback-specific half of the module IS gated, so
    // a classpath without Logback loses the appender install and keeps the client.
    try (ApplicationContext context = ApplicationContext.run()) {
      BeanDefinition<NtfyLogbackInstaller> definition =
          context.getBeanDefinition(NtfyLogbackInstaller.class);

      assertThat(definition.getAnnotationMetadata().hasAnnotation(Requires.class))
          .as("@Requires on the installer bean definition").isTrue();
    }
  }
}
