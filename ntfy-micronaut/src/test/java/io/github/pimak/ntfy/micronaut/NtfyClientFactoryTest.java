package io.github.pimak.ntfy.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.NtfyClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requirements;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.BeanDefinition;
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
