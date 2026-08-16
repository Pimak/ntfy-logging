package io.github.pimak.ntfy.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code ntfy.*} configuration binds onto {@link NtfyConfiguration} with the same key
 * names, types and defaults as the Spring starter's {@code NtfyProperties} — the whole point of the
 * class being a drop-in analogue.
 *
 * <p>Every context here is opened with {@code ntfy.enabled=false} or without a url/topic, so no
 * appender is ever installed on the JVM-wide root logger by a binding test; installation has its own
 * tests.
 */
class NtfyConfigurationBindingTest {

  @Test
  void noProperties_defaultsMatchTheEngineDefaults() {
    try (ApplicationContext context = ApplicationContext.run()) {
      NtfyConfiguration c = context.getBean(NtfyConfiguration.class);

      assertThat(c.getUrl()).isNull();
      assertThat(c.getTopic()).isNull();
      assertThat(c.getToken()).isNull();
      assertThat(c.getUsername()).isNull();
      assertThat(c.getPassword()).isNull();
      assertThat(c.getTitle()).isNull();
      assertThat(c.getAppName()).isNull();
      assertThat(c.getClickUrl()).isNull();
      assertThat(c.getActions()).isNull();
      assertThat(c.getLocale()).isNull();
      assertThat(c.getExcludedLoggers()).isNull();
      assertThat(c.getIncludeMdcKeys()).isNull();
      assertThat(c.getMaxStackFrames()).isEqualTo(5);
      assertThat(c.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
      assertThat(c.getRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
      assertThat(c.getMaxAlertsPerWindow()).isEqualTo(3);
      assertThat(c.getSuppressionWindow()).isEqualTo(Duration.ofMinutes(3));
      assertThat(c.getErrorPriority()).isEqualTo("high");
      assertThat(c.getDigestPriority()).isEqualTo("urgent");
      assertThat(c.getErrorTags()).isEqualTo("rotating_light");
      assertThat(c.getDigestTags()).isEqualTo("fire");
      // Level routing is opt-in: a null warn topic is exactly what keeps alerting ERROR-only, so
      // this null is the assertion that guards every existing Micronaut user's behaviour.
      assertThat(c.getWarnTopic()).isNull();
      assertThat(c.getWarnPriority()).isEqualTo("default");
      assertThat(c.getWarnTags()).isEqualTo("warning");
      assertThat(c.getAsyncQueueCapacity()).isEqualTo(1024);
      // The two defaults that changed with the engine's 2.0 behaviour — asserted explicitly so a
      // silent drift away from the Spring starter's roster fails here.
      assertThat(c.isEnabled()).isTrue();
      assertThat(c.isAsync()).isTrue();
      assertThat(c.isRequireHttpsForCredentials()).isTrue();
    }
  }

  @Test
  void kebabCaseKeys_bindEveryPropertyIncludingDurationsIntsAndBooleans() {
    Map<String, Object> properties = new LinkedHashMap<>();
    // A loopback https endpoint: credentials are bound and asserted below, and nothing can leave
    // the machine even if something were to publish.
    properties.put("ntfy.url", "https://127.0.0.1:1");
    properties.put("ntfy.topic", "alerts");
    properties.put("ntfy.token", "tk_binding");
    properties.put("ntfy.username", "binding-user");
    properties.put("ntfy.password", "binding-secret");
    properties.put("ntfy.title", "Bound title");
    properties.put("ntfy.app-name", "micronaut-binding");
    properties.put("ntfy.max-stack-frames", 9);
    properties.put("ntfy.connect-timeout", "7s");
    properties.put("ntfy.request-timeout", "11500ms");
    properties.put("ntfy.max-alerts-per-window", 17);
    properties.put("ntfy.suppression-window", "2m");
    properties.put("ntfy.error-priority", "max");
    properties.put("ntfy.digest-priority", "default");
    properties.put("ntfy.error-tags", "boom");
    properties.put("ntfy.digest-tags", "books");
    properties.put("ntfy.warn-topic", "alerts-warn");
    properties.put("ntfy.warn-priority", "low");
    properties.put("ntfy.warn-tags", "eyes");
    properties.put("ntfy.click-url", "https://example.invalid/runbook");
    properties.put("ntfy.actions", "view, Runbook, https://example.invalid/runbook");
    properties.put("ntfy.locale", "fr");
    properties.put("ntfy.excluded-loggers", "com.noisy,org.chatty");
    properties.put("ntfy.include-mdc-keys", "requestId,tenant");
    properties.put("ntfy.enabled", false);
    properties.put("ntfy.async", false);
    properties.put("ntfy.async-queue-capacity", 64);
    properties.put("ntfy.require-https-for-credentials", false);
    properties.put("ntfy.startup-ping", "probe");
    properties.put("ntfy.startup-ping-fail-fast", true);

    try (ApplicationContext context = ApplicationContext.run(properties)) {
      NtfyConfiguration c = context.getBean(NtfyConfiguration.class);

      assertThat(c.getUrl()).isEqualTo("https://127.0.0.1:1");
      assertThat(c.getTopic()).isEqualTo("alerts");
      assertThat(c.getToken()).isEqualTo("tk_binding");
      assertThat(c.getUsername()).isEqualTo("binding-user");
      assertThat(c.getPassword()).isEqualTo("binding-secret");
      assertThat(c.getTitle()).isEqualTo("Bound title");
      assertThat(c.getAppName()).isEqualTo("micronaut-binding");
      assertThat(c.getMaxStackFrames()).isEqualTo(9);
      // Micronaut converts its own duration syntax; both the "Ns" and the "Nms" forms must land as
      // real Durations, because the installer turns them straight into millis for the appender.
      assertThat(c.getConnectTimeout()).isEqualTo(Duration.ofSeconds(7));
      assertThat(c.getRequestTimeout()).isEqualTo(Duration.ofMillis(11_500));
      assertThat(c.getMaxAlertsPerWindow()).isEqualTo(17);
      assertThat(c.getSuppressionWindow()).isEqualTo(Duration.ofMinutes(2));
      assertThat(c.getErrorPriority()).isEqualTo("max");
      assertThat(c.getDigestPriority()).isEqualTo("default");
      assertThat(c.getErrorTags()).isEqualTo("boom");
      assertThat(c.getDigestTags()).isEqualTo("books");
      assertThat(c.getWarnTopic()).isEqualTo("alerts-warn");
      assertThat(c.getWarnPriority()).isEqualTo("low");
      assertThat(c.getWarnTags()).isEqualTo("eyes");
      assertThat(c.getClickUrl()).isEqualTo("https://example.invalid/runbook");
      assertThat(c.getActions()).isEqualTo("view, Runbook, https://example.invalid/runbook");
      assertThat(c.getLocale()).isEqualTo("fr");
      assertThat(c.getExcludedLoggers()).isEqualTo("com.noisy,org.chatty");
      assertThat(c.getIncludeMdcKeys()).isEqualTo("requestId,tenant");
      assertThat(c.isEnabled()).isFalse();
      assertThat(c.isAsync()).isFalse();
      assertThat(c.getAsyncQueueCapacity()).isEqualTo(64);
      assertThat(c.isRequireHttpsForCredentials()).isFalse();
      assertThat(c.getStartupPing()).isEqualTo("probe");
      assertThat(c.isStartupPingFailFast()).isTrue();
    }
  }

  @Test
  void startupPingDefaultsToOptedOut() {
    try (ApplicationContext context =
        ApplicationContext.run(Map.of("ntfy.url", "https://127.0.0.1:1", "ntfy.topic", "alerts"))) {
      NtfyConfiguration c = context.getBean(NtfyConfiguration.class);

      assertThat(c.getStartupPing()).isEqualTo("off");
      assertThat(c.isStartupPingFailFast()).isFalse();
    }
  }

  @Test
  void anUnrecognizedStartupPingModeDoesNotFailBeanCreation() {
    // Bound as a String, not an enum: Micronaut would fail bean instantiation on an unconvertible
    // enum value, turning one typo in application.yml into a dead application. The core parses it
    // leniently and the engine warns at start instead.
    try (ApplicationContext context =
        ApplicationContext.run(
            Map.of(
                "ntfy.url", "https://127.0.0.1:1",
                "ntfy.topic", "alerts",
                "ntfy.startup-ping", "prob"))) {
      assertThat(context.getBean(NtfyConfiguration.class).getStartupPing()).isEqualTo("prob");
    }
  }

  @Test
  void configurationFile_bindsTheSameKeysAsAProgrammaticPropertySource() {
    // src/test/resources/application-ntfybinding.properties, loaded because the "ntfybinding"
    // environment is active. This is the realistic path — the same keys an application.yml would
    // carry — and it proves binding does not depend on the programmatic PropertySource above.
    try (ApplicationContext context = ApplicationContext.run("ntfybinding")) {
      NtfyConfiguration c = context.getBean(NtfyConfiguration.class);

      assertThat(c.getUrl()).isEqualTo("https://127.0.0.1:1");
      assertThat(c.getTopic()).isEqualTo("file-topic");
      assertThat(c.getAppName()).isEqualTo("from-config-file");
      assertThat(c.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(c.getSuppressionWindow()).isEqualTo(Duration.ofSeconds(90));
      assertThat(c.getMaxStackFrames()).isEqualTo(12);
      assertThat(c.getWarnTopic()).isEqualTo("file-warn-topic");
      assertThat(c.isAsync()).isFalse();
      assertThat(c.isEnabled()).isFalse();
    }
  }
}
