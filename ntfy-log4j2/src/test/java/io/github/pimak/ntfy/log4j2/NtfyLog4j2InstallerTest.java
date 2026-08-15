package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end programmatic install against a REAL, isolated {@link LoggerContext} (never the JVM's
 * global one, which surefire shares with every other test): system properties configure ntfy, the
 * installer attaches a started appender to the root logger, a log4j2 reconfiguration must not lose
 * it, and {@code uninstall} must leave nothing behind — no appender, no listener, no live engine.
 *
 * <p>Config is injected via {@code ntfy.*} system properties (the highest-precedence {@code
 * ConfigLoader} layer) and cleared after each test. No HTTP endpoint is ever contacted: nothing is
 * logged through the installed appender here.
 */
class NtfyLog4j2InstallerTest {

  private LoggerContext context;

  @BeforeEach
  void startIsolatedContext() {
    context = new LoggerContext("ntfy-installer-test");
    context.start();
  }

  @AfterEach
  void cleanUp() {
    System.clearProperty("ntfy.url");
    System.clearProperty("ntfy.topic");
    context.stop();
  }

  private static void configureAmbientNtfy() {
    // Port 1 is never contacted — the installer only starts the engine, which performs no I/O.
    System.setProperty("ntfy.url", "http://localhost:1");
    System.setProperty("ntfy.topic", "installer-alerts");
  }

  @Test
  void install_configuredEnvironment_attachesStartedAppenderToRootLogger() {
    configureAmbientNtfy();

    Optional<NtfyLog4j2Appender> installed = NtfyLog4j2Installer.install(context);

    assertThat(installed).isPresent();
    NtfyLog4j2Appender appender = installed.get();
    try {
      assertThat(appender.isStarted()).isTrue();
      // Explicitly typed: Configuration.getAppender is generic, so an inline call would leave the
      // AssertJ overload ambiguous.
      Appender registered = context.getConfiguration().getAppender(NtfyLog4j2Installer.APPENDER_NAME);
      assertThat(registered).isSameAs(appender);
      assertThat(context.getConfiguration().getRootLogger().getAppenders())
          .containsKey(NtfyLog4j2Installer.APPENDER_NAME);
    } finally {
      NtfyLog4j2Installer.uninstall(context, appender);
    }
  }

  @Test
  void install_unconfiguredEnvironment_installsNothing() {
    Optional<NtfyLog4j2Appender> installed = NtfyLog4j2Installer.install(context);

    assertThat(installed).isEmpty();
    assertThat(context.getConfiguration().getRootLogger().getAppenders())
        .doesNotContainKey(NtfyLog4j2Installer.APPENDER_NAME);
  }

  @Test
  void reconfiguration_reattachesAndRestartsTheAppenderWithTheSameCounters() {
    configureAmbientNtfy();
    NtfyLog4j2Appender appender = NtfyLog4j2Installer.install(context).orElseThrow();
    var countersBefore = appender.getCounters();

    // A log4j2 reconfiguration swaps in a brand-new Configuration and STOPS the outgoing one,
    // which stops every appender registered in it — including ours. Exactly what the reattach
    // listener exists to repair.
    context.reconfigure();

    try {
      assertThat(context.getConfiguration().getRootLogger().getAppenders())
          .as("reattach listener must re-add the appender after a reconfiguration")
          .containsKey(NtfyLog4j2Installer.APPENDER_NAME);
      assertThat(context.getConfiguration().getRootLogger().getAppenders())
          .containsEntry(NtfyLog4j2Installer.APPENDER_NAME, appender);
      assertThat(appender.isStarted()).isTrue();
      assertThat(appender.getCounters())
          .as("same PipelineCounters instance across the reconfiguration")
          .isSameAs(countersBefore);
    } finally {
      NtfyLog4j2Installer.uninstall(context, appender);
    }
  }

  @Test
  void uninstall_detachesStopsAndDeregistersTheReattachListener() {
    configureAmbientNtfy();
    NtfyLog4j2Appender appender = NtfyLog4j2Installer.install(context).orElseThrow();

    NtfyLog4j2Installer.uninstall(context, appender);

    assertThat(appender.isStarted()).isFalse();
    assertThat(context.getConfiguration().getRootLogger().getAppenders())
        .doesNotContainKey(NtfyLog4j2Installer.APPENDER_NAME);

    // The listener must be gone too: otherwise a later reconfiguration would resurrect a stopped
    // appender (and with it a fresh engine) long after the application asked for teardown.
    context.reconfigure();
    assertThat(context.getConfiguration().getRootLogger().getAppenders())
        .doesNotContainKey(NtfyLog4j2Installer.APPENDER_NAME);
    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void uninstall_calledTwice_isSafe() {
    configureAmbientNtfy();
    NtfyLog4j2Appender appender = NtfyLog4j2Installer.install(context).orElseThrow();

    NtfyLog4j2Installer.uninstall(context, appender);
    NtfyLog4j2Installer.uninstall(context, appender);

    assertThat(appender.isStarted()).isFalse();
  }
}
