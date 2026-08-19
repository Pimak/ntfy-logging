package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies Spring Boot's relaxed binding maps {@code ntfy.startup-ping} and {@code
 * ntfy.startup-ping-fail-fast} onto {@link NtfyProperties}, that the opt-in defaults hold when the
 * keys are absent, and that a typo'd mode cannot fail the context refresh.
 */
class NtfyPropertiesStartupPingBindingTest {

  private static NtfyProperties bind(Map<String, String> props) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
    return new Binder(source).bind("ntfy", NtfyProperties.class).orElseGet(NtfyProperties::new);
  }

  @Test
  void startupPingBindsWithRelaxedKebabCase() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping", "probe");

    assertThat(bind(props).getStartupPing()).isEqualTo("probe");
  }

  @Test
  void startupPingFailFastBindsWithRelaxedKebabCase() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping-fail-fast", "true");

    assertThat(bind(props).isStartupPingFailFast()).isTrue();
  }

  @Test
  void everySelfTestKeyDefaultsToOptedOutWhenAbsent() {
    NtfyProperties bound = bind(new HashMap<>());

    // Both modes are opt-IN, so an untouched Spring app makes no boot-time request at all...
    assertThat(bound.getStartupPing()).isEqualTo("off");
    assertThat(bound.getStartupPingWarn()).isEqualTo("off");
    assertThat(bound.isStartupPingFailFast()).isFalse();
    // ...while the failure notification is the single opt-OUT in the feature.
    assertThat(bound.isStartupPingNotifyFailures()).isTrue();
  }

  @Test
  void startupPingWarnBindsIndependentlyOfStartupPing() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping-warn", "publish");

    NtfyProperties bound = bind(props);

    // Only the WARN route is opted in: enabling one route must never opt the other in, since in
    // publish mode each costs its own notification per boot.
    assertThat(bound.getStartupPingWarn()).isEqualTo("publish");
    assertThat(bound.getStartupPing()).isEqualTo("off");
  }

  @Test
  void notifyFailuresBindsAsAnOptOut() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping-notify-failures", "false");

    assertThat(bind(props).isStartupPingNotifyFailures()).isFalse();
  }

  @Test
  void anUnrecognizedModeBindsWithoutFailingTheContext() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping", "prob");

    // Binding as String, not as an enum, is what keeps one typo in application.yml from turning
    // into a failed context refresh; the core parses it leniently and warns at engine start.
    assertThatCode(() -> bind(props)).doesNotThrowAnyException();
    assertThat(bind(props).getStartupPing()).isEqualTo("prob");
  }

  @Test
  void everyBoundValueSurvivesTranslationIntoTheCoreConfig() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping", "publish");
    props.put("ntfy.startup-ping-warn", "probe");
    props.put("ntfy.startup-ping-fail-fast", "true");
    props.put("ntfy.startup-ping-notify-failures", "false");

    NtfyProperties bound = bind(props);

    // The adapter hands all four to the appender, which hands them to this builder — assert the
    // whole set, so a key that binds but is then dropped on the way to the engine still fails here.
    io.github.pimak.ntfy.core.NtfyConfig config =
        io.github.pimak.ntfy.core.NtfyConfig.builder()
            .startupPing(bound.getStartupPing())
            .startupPingWarn(bound.getStartupPingWarn())
            .startupPingFailFast(bound.isStartupPingFailFast())
            .startupPingNotifyFailures(bound.isStartupPingNotifyFailures())
            .build();

    assertThat(config.getStartupPing()).isEqualTo(io.github.pimak.ntfy.core.StartupPingMode.PUBLISH);
    assertThat(config.getStartupPingWarn()).isEqualTo(io.github.pimak.ntfy.core.StartupPingMode.PROBE);
    assertThat(config.isStartupPingFailFast()).isTrue();
    assertThat(config.isStartupPingNotifyFailures()).isFalse();
  }
}
