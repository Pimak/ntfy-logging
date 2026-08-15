package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import io.github.pimak.ntfy.core.NtfyClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies that the starter picks its logging backend from the classpath rather than being wired to
 * one, without booting a full application.
 *
 * <p>The regression this locks in is the reason the module exists: the auto-configuration used to
 * carry a class-level {@code @ConditionalOnClass} on {@code ch.qos.logback.classic.LoggerContext},
 * so a {@code spring-boot-starter-log4j2} application got nothing at all — not even the {@link
 * NtfyClient} bean, which has no connection to Logback.
 *
 * <p>Blank {@code url}/{@code topic} keep every case offline: no appender is installed, which is
 * irrelevant to the conditional wiring under test here.
 */
class NtfyBackendSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NtfyAutoConfiguration.class))
          .withPropertyValues("ntfy.enabled=true");

  @Test
  void logbackOnClasspath_logbackBackendContributed() {
    runner
        .withClassLoader(new FilteredClassLoader(org.apache.logging.log4j.core.LoggerContext.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(NtfyBackend.class);
          assertThat(context.getBean(NtfyBackend.class).displayName()).isEqualTo("Logback");
        });
  }

  @Test
  void log4j2OnClasspathWithoutLogback_log4j2BackendContributed() {
    runner
        .withClassLoader(new FilteredClassLoader(LoggerContext.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(NtfyBackend.class);
          assertThat(context.getBean(NtfyBackend.class).displayName()).isEqualTo("Log4j2");
        });
  }

  @Test
  void log4j2OnClasspathWithoutLogback_ntfyClientBeanStillExposed() {
    // The whole point of dropping the class-level @ConditionalOnClass: manual notifications must
    // work on a Log4j2 application even though the appender path is a different backend.
    runner
        .withClassLoader(new FilteredClassLoader(LoggerContext.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(NtfyClient.class);
        });
  }

  @Test
  void noSupportedBackend_contextStartsAndClientBeanStillExposed() {
    runner
        .withClassLoader(new FilteredClassLoader(
            LoggerContext.class, org.apache.logging.log4j.core.LoggerContext.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(NtfyBackend.class);
          assertThat(context).hasSingleBean(NtfyClient.class);
        });
  }

  @Test
  void bothBackendsOnClasspath_logbackIsSelectedFirst() {
    // Bridges legitimately put both on the classpath. The @Order on the nested @Bean methods makes
    // the choice deterministic, and Logback must keep winning so existing applications are
    // unaffected by Log4j2 support being added.
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeanProvider(NtfyBackend.class).orderedStream().toList())
          .extracting(NtfyBackend::displayName)
          .containsExactly("Logback", "Log4j2");
    });
  }
}
