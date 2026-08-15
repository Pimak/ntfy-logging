package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A backend that resolves a configuration the engine refuses must install nothing at all.
 *
 * <p>The realistic trigger is the strict transport mode that became the default in 2.0: a token
 * over a plain {@code http://} endpoint makes {@code AlertEngine.start()} decline, so the appender
 * never reaches the started state. Attaching it regardless would wire a stopped appender to the
 * root logger — answered with an internal warning or error on every subsequent log event — and
 * would leave the auto-configuration believing an installation happened, so shutdown would tear
 * down something that was never built.
 */
class NtfyBackendRefusedInstallTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NtfyAutoConfiguration.class))
          .withPropertyValues(
              "ntfy.enabled=true",
              // Never contacted: the engine refuses before acquiring any HTTP resource.
              "ntfy.url=http://localhost:1",
              "ntfy.topic=alerts",
              "ntfy.token=tk_secret");

  @Test
  void engineRefusesActivation_noAppenderIsAttachedToTheRootLogger() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(rootAppender()).isNull();
    });
  }

  @Test
  void engineRefusesActivation_countersReportNothingInstalled() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      // Null (not a live counters handle) is what keeps the Micrometer meters reading 0 instead of
      // pointing at an appender that never started.
      assertThat(context.getBean(NtfyAutoConfiguration.class).installedCounters()).isNull();
    });
  }

  @Test
  void engineRefusesActivation_contextShutdownIsClean() {
    // destroy() must not try to uninstall a non-installation; the runner closes the context at the
    // end of run(), so an exception there would surface as a failure here.
    runner.run(context -> assertThat(context).hasNotFailed());
    assertThat(rootAppender()).as("nothing may be left attached after shutdown").isNull();
  }

  private static ch.qos.logback.core.Appender<?> rootAppender() {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    return lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        .getAppender(NtfyBackend.APPENDER_NAME);
  }
}
