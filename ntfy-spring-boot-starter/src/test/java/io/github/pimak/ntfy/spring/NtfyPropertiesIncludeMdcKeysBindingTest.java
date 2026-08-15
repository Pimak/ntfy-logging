package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies Spring Boot's relaxed binding maps the MDC allow-list key ({@code
 * ntfy.include-mdc-keys}, and its camelCase spelling) onto {@link NtfyProperties}, and that the
 * property stays {@code null} when absent — the unset default that means "publish no MDC value at
 * all". The raw comma-separated string is bound verbatim: splitting and trimming belong to the
 * downstream consumers ({@code NtfyConfig.Builder#includeMdcKeysCsv} and the appender setter), so
 * exactly one implementation of that parsing exists.
 */
class NtfyPropertiesIncludeMdcKeysBindingTest {

  private static NtfyProperties bind(Map<String, String> props) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
    return new Binder(source)
        .bind("ntfy", NtfyProperties.class)
        .orElseGet(NtfyProperties::new);
  }

  @Test
  void includeMdcKeys_bindsFromKebabCaseProperty() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.include-mdc-keys", "requestId,tenant");

    NtfyProperties bound = bind(props);

    assertThat(bound.getIncludeMdcKeys()).isEqualTo("requestId,tenant");
  }

  @Test
  void includeMdcKeys_bindsFromCamelCaseProperty() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.includeMdcKeys", "requestId,tenant");

    NtfyProperties bound = bind(props);

    assertThat(bound.getIncludeMdcKeys()).isEqualTo("requestId,tenant");
  }

  @Test
  void includeMdcKeys_defaultsToNull_whenUnset() {
    NtfyProperties bound = bind(new HashMap<>());

    assertThat(bound.getIncludeMdcKeys()).isNull();
  }
}
