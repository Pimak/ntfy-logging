package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies Spring Boot's relaxed binding maps {@code ntfy.require-https-for-credentials} onto
 * {@link NtfyProperties}, and that the default ({@code false}) holds when the key is absent.
 */
class NtfyPropertiesRequireHttpsBindingTest {

  private static NtfyProperties bind(Map<String, String> props) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
    return new Binder(source)
        .bind("ntfy", NtfyProperties.class)
        .orElseGet(NtfyProperties::new);
  }

  @Test
  void requireHttpsForCredentialsBindsWithRelaxedKebabCase() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.require-https-for-credentials", "true");

    NtfyProperties bound = bind(props);

    assertThat(bound.isRequireHttpsForCredentials()).isTrue();
  }

  @Test
  void requireHttpsForCredentialsDefaultsToFalseWhenAbsent() {
    NtfyProperties bound = bind(new HashMap<>());

    assertThat(bound.isRequireHttpsForCredentials()).isFalse();
  }
}
