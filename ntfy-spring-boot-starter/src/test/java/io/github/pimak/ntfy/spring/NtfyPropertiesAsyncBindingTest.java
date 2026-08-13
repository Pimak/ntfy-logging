package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies Spring Boot's relaxed binding maps the async delivery keys ({@code ntfy.async} /
 * {@code ntfy.async-queue-capacity}) onto {@link NtfyProperties}, and that the defaults hold when
 * the keys are absent.
 */
class NtfyPropertiesAsyncBindingTest {

  private static NtfyProperties bind(Map<String, String> props) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
    return new Binder(source)
        .bind("ntfy", NtfyProperties.class)
        .orElseGet(NtfyProperties::new);
  }

  @Test
  void asyncKeysBindWithRelaxedKebabCase() {
    // Binds the non-default "false" (since 2.0) so a passing assertion can only come from the key
    // actually reaching the field, never from the default standing.
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.async", "false");
    props.put("ntfy.async-queue-capacity", "512");

    NtfyProperties bound = bind(props);

    assertThat(bound.isAsync()).isFalse();
    assertThat(bound.getAsyncQueueCapacity()).isEqualTo(512);
  }

  @Test
  void asyncDefaultsWhenAbsent() {
    NtfyProperties bound = bind(new HashMap<>());

    assertThat(bound.isAsync()).isTrue();
    assertThat(bound.getAsyncQueueCapacity()).isEqualTo(1024);
  }
}
