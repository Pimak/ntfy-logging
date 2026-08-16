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
  void bothDefaultToOptedOutWhenAbsent() {
    NtfyProperties bound = bind(new HashMap<>());

    assertThat(bound.getStartupPing()).isEqualTo("off");
    assertThat(bound.isStartupPingFailFast()).isFalse();
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
  void theBoundValueSurvivesTranslationIntoTheCoreConfig() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.startup-ping", "publish");
    props.put("ntfy.startup-ping-fail-fast", "true");

    NtfyProperties bound = bind(props);

    assertThat(
            io.github.pimak.ntfy.core.NtfyConfig.builder()
                .startupPing(bound.getStartupPing())
                .startupPingFailFast(bound.isStartupPingFailFast())
                .build()
                .getStartupPing())
        .isEqualTo(io.github.pimak.ntfy.core.StartupPingMode.PUBLISH);
  }
}
