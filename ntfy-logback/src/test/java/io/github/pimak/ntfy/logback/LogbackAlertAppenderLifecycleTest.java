package io.github.pimak.ntfy.logback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import ch.qos.logback.core.ContextBase;
import ch.qos.logback.core.status.Status;

/**
 * Proves the thin appender delegates its silent-inactive vs loud-partial-config distinction to the
 * engine and mirrors the engine's activation state through {@code isStarted()}. No HTTP call is ever
 * made — only {@code start()}/{@code stop()}. Ported from the original {@code
 * NtfyAlertAppenderLifecycleTest}; the status message text now originates in the engine ("ntfy alert
 * engine ...") and duration setters take strings.
 */
class LogbackAlertAppenderLifecycleTest {

  // Literal engine status strings (ntfy-core's AlertMessages is package-private, so the exact text
  // is pinned here — a drift in either side turns these assertions red on purpose).
  private static final String STATUS_DISABLED_UNCONFIGURED =
      "ntfy alert engine not configured (url/topic unset) — inactive";
  private static final String STATUS_DISABLED_PARTIAL_CONFIG =
      "url set but topic missing — engine disabled";
  private static final String STATUS_TOKEN_AND_BASIC_BOTH_SET =
      "both token and username/password configured — token takes precedence";

  private static LogbackAlertAppender newAppender() {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new ContextBase());
    return appender;
  }

  @Test
  void start_urlAndTopicBothUnset_staysInactiveWithInfoStatusOnly() {
    LogbackAlertAppender appender = newAppender();

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).getLevel()).isEqualTo(Status.INFO);
    assertThat(statuses.get(0).getMessage()).isEqualTo(STATUS_DISABLED_UNCONFIGURED);
  }

  @Test
  void start_onlyUrlSet_staysInactiveWithPartialConfigWarn() {
    LogbackAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).getLevel()).isEqualTo(Status.WARN);
    assertThat(statuses.get(0).getMessage()).isEqualTo(STATUS_DISABLED_PARTIAL_CONFIG);
  }

  @Test
  void start_onlyTopicSet_staysInactiveWithPartialConfigWarn() {
    LogbackAlertAppender appender = newAppender();
    appender.setTopic("alerts");

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).hasSize(1);
    assertThat(statuses.get(0).getLevel()).isEqualTo(Status.WARN);
    assertThat(statuses.get(0).getMessage()).isEqualTo(STATUS_DISABLED_PARTIAL_CONFIG);
  }

  @Test
  void start_urlAndTopicSet_activatesWithInfoStatusNeverContainingToken() {
    LogbackAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.setToken("SECRET-TOKEN-XYZ");

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .noneMatch(m -> m.contains("SECRET-TOKEN-XYZ"));
    assertThat(statuses)
        .extracting(Status::getMessage)
        .anyMatch(m -> m.startsWith("ntfy alert engine ACTIVE"));

    appender.stop();
  }

  @Test
  void start_tokenAndBasicAuthBothSet_activatesWithOneTimeWarnButStillStarts() {
    LogbackAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.setToken("tok");
    appender.setUsername("user");
    appender.setPassword("pass");

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    List<Status> statuses = appender.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .filteredOn(s -> s.getMessage().equals(STATUS_TOKEN_AND_BASIC_BOTH_SET))
        .hasSize(1);

    appender.stop();
  }

  @Test
  void stop_neverStarted_isSafeNoException() {
    LogbackAlertAppender appender = newAppender();

    appender.stop();

    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void setLocale_isAcceptedAndAppenderStillActivates() {
    LogbackAlertAppender appender = newAppender();
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.setLocale("fr");

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    // English-default status text is not asserted here; this only proves the <locale> Joran setter
    // is wired and never blocks activation. A bad/unknown tag would simply fall back to English.
    appender.stop();
  }

  @Test
  void timeoutSetters_acceptDurationStrings() {
    LogbackAlertAppender appender = newAppender();

    appender.setConnectTimeout("1234"); // bare int = millis
    appender.setRequestTimeout("3s");
    appender.setSuppressionWindow("2m");
    appender.setUrl("http://localhost:9999");
    appender.setTopic("alerts");
    appender.start();

    assertThat(appender.isStarted()).isTrue();
    appender.stop();
  }
}
