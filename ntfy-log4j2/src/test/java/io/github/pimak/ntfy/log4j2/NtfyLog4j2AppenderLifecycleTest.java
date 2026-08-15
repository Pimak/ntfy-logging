package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the thin appender delegates its silent-inactive vs loud-partial-config distinction to the
 * engine and mirrors the engine's activation state through {@code isStarted()}, that no credential
 * ever reaches the StatusLogger, and that every {@code String}-typed plugin attribute is parsed
 * defensively. No HTTP call is ever made — only {@code start()}/{@code stop()}.
 */
class NtfyLog4j2AppenderLifecycleTest {

  // Literal engine status strings (ntfy-core's AlertMessages is package-private, so the exact text
  // is pinned here — a drift in either side turns these assertions red on purpose).
  private static final String STATUS_DISABLED_UNCONFIGURED =
      "ntfy alert engine not configured (url/topic unset) — inactive";
  private static final String STATUS_DISABLED_PARTIAL_CONFIG =
      "url set but topic missing — engine disabled";
  private static final String STATUS_TOKEN_AND_BASIC_BOTH_SET =
      "both token and username/password configured — token takes precedence";

  private RecordingStatusListener status;

  @BeforeEach
  void attachStatusListener() {
    status = RecordingStatusListener.attach();
  }

  @AfterEach
  void detachStatusListener() {
    status.detach();
  }

  private static NtfyLog4j2Appender.Builder builder() {
    return NtfyLog4j2Appender.newBuilder().setName("ntfy");
  }

  @Test
  void start_urlAndTopicBothUnset_staysInactiveWithInfoStatusOnly() {
    NtfyLog4j2Appender appender = builder().build();

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    assertThat(status.messagesAt(Level.INFO)).contains(STATUS_DISABLED_UNCONFIGURED);
    assertThat(status.messagesAt(Level.WARN)).isEmpty();
  }

  @Test
  void start_onlyUrlSet_staysInactiveWithPartialConfigWarn() {
    NtfyLog4j2Appender appender = builder().setUrl("http://localhost:9999").build();

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    assertThat(status.messagesAt(Level.WARN)).contains(STATUS_DISABLED_PARTIAL_CONFIG);
  }

  @Test
  void start_onlyTopicSet_staysInactiveWithPartialConfigWarn() {
    NtfyLog4j2Appender appender = builder().setTopic("alerts").build();

    appender.start();

    assertThat(appender.isStarted()).isFalse();
    assertThat(status.messagesAt(Level.WARN)).contains(STATUS_DISABLED_PARTIAL_CONFIG);
  }

  @Test
  void start_urlAndTopicSet_activatesWithStatusNeverContainingToken() {
    // https: a token over plain http would now (2.0) refuse activation before reaching the
    // ACTIVE line this test is about.
    NtfyLog4j2Appender appender =
        builder()
            .setUrl("https://localhost:9999")
            .setTopic("alerts")
            .setToken("SECRET-TOKEN-XYZ")
            .build();

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    assertThat(status.messages()).noneMatch(m -> m.contains("SECRET-TOKEN-XYZ"));
    assertThat(status.messages()).anyMatch(m -> m.startsWith("ntfy alert engine ACTIVE"));

    appender.stop();
  }

  @Test
  void start_tokenAndBasicAuthBothSet_activatesWithOneTimeWarnButStillStarts() {
    // https: a token over plain http would now (2.0) refuse activation before the overlap warn.
    NtfyLog4j2Appender appender =
        builder()
            .setUrl("https://localhost:9999")
            .setTopic("alerts")
            .setToken("TOKEN-SECRET-1")
            .setUsername("USER-SECRET-2")
            .setPassword("PASSWORD-SECRET-3")
            .build();

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    // "one-time": the overlap must be reported exactly once, not on every publish.
    assertThat(status.messages()).filteredOn(STATUS_TOKEN_AND_BASIC_BOTH_SET::equals).hasSize(1);
    assertThat(status.messages())
        .noneMatch(
            m ->
                m.contains("TOKEN-SECRET-1")
                    || m.contains("USER-SECRET-2")
                    || m.contains("PASSWORD-SECRET-3"));

    appender.stop();
  }

  @Test
  void stop_neverStarted_isSafeNoException() {
    NtfyLog4j2Appender appender = builder().build();

    appender.stop();

    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void stop_calledTwice_isIdempotent() {
    NtfyLog4j2Appender appender =
        builder().setUrl("http://localhost:9999").setTopic("alerts").build();
    appender.start();

    appender.stop();
    appender.stop();

    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void durationAttributes_acceptEveryDurationForm() {
    NtfyLog4j2Appender appender =
        builder()
            .setUrl("http://localhost:9999")
            .setTopic("alerts")
            .setConnectTimeout("1234") // bare int = millis
            .setRequestTimeout("3s")
            .setSuppressionWindow("2m")
            .build();

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    appender.stop();
  }

  @Test
  void localeAttribute_isAcceptedAndAppenderStillActivates() {
    NtfyLog4j2Appender appender =
        builder().setUrl("http://localhost:9999").setTopic("alerts").setLocale("fr").build();

    appender.start();

    // English-default status text is not asserted here; this only proves the locale attribute is
    // wired and never blocks activation. A bad/unknown tag simply falls back to English.
    assertThat(appender.isStarted()).isTrue();
    appender.stop();
  }

  @Test
  void malformedIntegerAttribute_warnsAndKeepsTheDefaultInsteadOfThrowing() {
    NtfyLog4j2Appender appender =
        builder()
            .setUrl("http://localhost:9999")
            .setTopic("alerts")
            .setMaxStackFrames("not-a-number")
            .build();

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    assertThat(status.messagesAt(Level.WARN))
        .anyMatch(m -> m.contains("maxStackFrames") && m.contains("keeping the default"));
    appender.stop();
  }

  @Test
  void malformedDurationAttribute_warnsAndKeepsTheDefaultInsteadOfThrowing() {
    NtfyLog4j2Appender appender =
        builder()
            .setUrl("http://localhost:9999")
            .setTopic("alerts")
            .setSuppressionWindow("every-so-often")
            .build();

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    assertThat(status.messagesAt(Level.WARN))
        .anyMatch(m -> m.contains("suppressionWindow") && m.contains("keeping the default"));
    appender.stop();
  }

  @Test
  void nonLiteralBooleanAttribute_warnsInsteadOfSilentlyDisablingAlerting() {
    // Boolean.parseBoolean("yes") is false; without the literal guard this would silently switch
    // alerting off with no diagnostic whatsoever.
    NtfyLog4j2Appender appender =
        builder().setUrl("http://localhost:9999").setTopic("alerts").setEnabled("yes").build();

    appender.start();

    assertThat(appender.isStarted()).isTrue();
    assertThat(status.messagesAt(Level.WARN))
        .anyMatch(m -> m.contains("enabled") && m.contains("keeping the default"));
    appender.stop();
  }

  @Test
  void enabledFalse_keepsTheEngineInert() {
    NtfyLog4j2Appender appender =
        builder().setUrl("http://localhost:9999").setTopic("alerts").setEnabled("false").build();

    appender.start();

    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void build_withoutName_returnsNullWithAWarnInsteadOfThrowing() {
    // log4j2's contract for an unbuildable element: drop the appender, keep the configuration.
    NtfyLog4j2Appender appender = NtfyLog4j2Appender.newBuilder().setUrl("http://x").build();

    assertThat(appender).isNull();
    assertThat(status.messagesAt(Level.WARN)).anyMatch(m -> m.contains("requires a name"));
  }

  @Test
  void getCounters_isStableAcrossStopStartCycles() {
    NtfyLog4j2Appender appender =
        builder().setUrl("http://localhost:9999").setTopic("alerts").build();
    appender.start();

    var before = appender.getCounters();
    appender.stop();
    appender.start();

    assertThat(appender.getCounters()).isSameAs(before);
    appender.stop();
  }
}
