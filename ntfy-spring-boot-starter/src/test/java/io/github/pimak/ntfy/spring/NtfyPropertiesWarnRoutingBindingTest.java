package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies Spring Boot's relaxed binding maps the three level-routing keys ({@code ntfy.warn-topic}
 * / {@code warn-priority} / {@code warn-tags}, and their camelCase spellings) onto {@link
 * NtfyProperties}, and — the assertion that matters most to existing users — that {@code warnTopic}
 * stays {@code null} when absent, since that null IS what keeps alerting ERROR-only.
 */
class NtfyPropertiesWarnRoutingBindingTest {

  private static NtfyProperties bind(Map<String, String> props) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
    return new Binder(source)
        .bind("ntfy", NtfyProperties.class)
        .orElseGet(NtfyProperties::new);
  }

  @Test
  void warnRoutingKeys_bindFromKebabCaseProperties() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.warn-topic", "my-app-warnings");
    props.put("ntfy.warn-priority", "low");
    props.put("ntfy.warn-tags", "warning,eyes");

    NtfyProperties bound = bind(props);

    assertThat(bound.getWarnTopic()).isEqualTo("my-app-warnings");
    assertThat(bound.getWarnPriority()).isEqualTo("low");
    assertThat(bound.getWarnTags()).isEqualTo("warning,eyes");
  }

  @Test
  void warnRoutingKeys_bindFromCamelCaseProperties() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.warnTopic", "my-app-warnings");
    props.put("ntfy.warnPriority", "low");
    props.put("ntfy.warnTags", "eyes");

    NtfyProperties bound = bind(props);

    assertThat(bound.getWarnTopic()).isEqualTo("my-app-warnings");
    assertThat(bound.getWarnPriority()).isEqualTo("low");
    assertThat(bound.getWarnTags()).isEqualTo("eyes");
  }

  @Test
  void warnTopic_defaultsToNull_whichIsWhatKeepsAlertingErrorOnly() {
    NtfyProperties bound = bind(new HashMap<>());

    assertThat(bound.getWarnTopic()).isNull();
    // The styling defaults exist regardless; they are only ever consulted once a topic is named.
    assertThat(bound.getWarnPriority()).isEqualTo("default");
    assertThat(bound.getWarnTags()).isEqualTo("warning");
  }
}
