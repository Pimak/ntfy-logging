package io.github.pimak.ntfy.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.LoggerContext;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

/**
 * The paths on which {@link NtfyLogbackInstaller} must install nothing: an unconfigured or disabled
 * application, and one whose SLF4J binding is not Logback. None of them may throw, and none of them
 * may leave an appender behind on the JVM-wide root logger the rest of the suite shares.
 */
class NtfyLogbackInstallerTest {

  @AfterEach
  void assertRootLoggerIsClean() {
    assertThat(rootAppender())
        .as("no ntfy-auto appender left on the root logger by a skip-path test")
        .isNull();
  }

  @Test
  void blankUrlAndTopic_nothingIsInstalled() {
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(context.getBean(NtfyLogbackInstaller.class)).isNotNull();
      assertThat(rootAppender()).as("ntfy-auto appender").isNull();
    }
  }

  @Test
  void topicWithoutUrl_nothingIsInstalled() {
    try (ApplicationContext context = ApplicationContext.run(Map.of("ntfy.topic", "alerts"))) {
      assertThat(context.getBean(NtfyLogbackInstaller.class)).isNotNull();
      assertThat(rootAppender()).as("ntfy-auto appender").isNull();
    }
  }

  @Test
  void disabled_nothingIsInstalledEvenWithUrlAndTopic() {
    Map<String, Object> properties = Map.of(
        "ntfy.enabled", false,
        "ntfy.url", "http://127.0.0.1:1",
        "ntfy.topic", "alerts");
    try (ApplicationContext context = ApplicationContext.run(properties)) {
      assertThat(context.getBean(NtfyLogbackInstaller.class)).isNotNull();
      assertThat(rootAppender()).as("ntfy-auto appender").isNull();
    }
  }

  @Test
  @Timeout(30)
  void nonLogbackSlf4jBackend_skipsInstallationWithoutThrowing() {
    // A fully configured installer handed an ILoggerFactory that is not a Logback LoggerContext:
    // the module must warn and carry on, never throw and never disable the client. Calling the
    // package-private install(ILoggerFactory) directly is the only way to exercise this, since the
    // test JVM's own SLF4J binding is (and must stay) Logback.
    NtfyConfiguration configuration = new NtfyConfiguration();
    configuration.setUrl("http://127.0.0.1:1");
    configuration.setTopic("alerts");
    NtfyLogbackInstaller installer = new NtfyLogbackInstaller(configuration);

    assertThatCode(() -> {
      assertThat(installer.install(new NOPLoggerFactory()))
          .as("install() on a non-Logback backend").isFalse();
      // Idempotent teardown of an installation that never happened.
      installer.uninstall();
    }).doesNotThrowAnyException();

    assertThat(rootAppender()).as("ntfy-auto appender on the real root logger").isNull();
  }

  private static Object rootAppender() {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    return lc.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(NtfyLogbackInstaller.APPENDER_NAME);
  }
}
