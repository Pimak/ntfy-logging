package io.github.pimak.ntfy.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.NtfyClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The consumer-facing shape of the module: a {@code @MicronautTest} application context, {@code
 * ntfy.*} in configuration, and the beans simply injected. Deliberately configured WITHOUT a url so
 * this test installs no appender on the shared root logger — it asserts injection and binding only.
 */
@MicronautTest
@Property(name = "ntfy.topic", value = "injected-topic")
@Property(name = "ntfy.app-name", value = "injected-app")
@Property(name = "ntfy.request-timeout", value = "4s")
class NtfyClientInjectionTest {

  @Inject
  NtfyClient ntfyClient;

  @Inject
  NtfyConfiguration configuration;

  @Test
  void micronautTestContext_injectsTheNtfyClientBean() {
    assertThat(ntfyClient).as("injected NtfyClient bean").isNotNull();
  }

  @Test
  void micronautTestContext_injectsTheBoundConfiguration() {
    assertThat(configuration.getTopic()).isEqualTo("injected-topic");
    assertThat(configuration.getAppName()).isEqualTo("injected-app");
    assertThat(configuration.getRequestTimeout()).isEqualTo(Duration.ofSeconds(4));
  }
}
