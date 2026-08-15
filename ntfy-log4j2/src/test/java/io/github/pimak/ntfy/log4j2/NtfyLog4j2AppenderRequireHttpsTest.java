package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code requireHttpsForCredentials} plugin attribute reaches the built {@link
 * io.github.pimak.ntfy.core.NtfyConfig}: with the attribute untouched, strict mode (the default
 * since 2.0) refuses activation for a token over a plain {@code http://} URL — fixed refusal
 * diagnostic, no publish attempt, and the appender therefore never reports itself started; with the
 * explicit {@code false} opt-out the pre-2.0 warn-and-activate behavior holds.
 */
class NtfyLog4j2AppenderRequireHttpsTest {

  private static final String REFUSAL_FRAGMENT = "require-https-for-credentials is enabled";

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
    return NtfyLog4j2Appender.newBuilder()
        .setName("ntfy")
        // Never contacted: strict mode refuses before any HTTP resource is acquired, and the
        // default-mode test only starts/stops without appending.
        .setUrl("http://localhost:1")
        .setTopic("alerts")
        .setToken("tk_secret");
  }

  @Test
  void defaultStrictMode_tokenOverPlainHttp_refusesActivation() {
    // The attribute is deliberately untouched: refusal must be the default since 2.0.
    NtfyLog4j2Appender appender = builder().build();

    appender.start();
    appender.stop();

    assertThat(appender.isStarted()).isFalse();
    assertThat(status.messages()).anyMatch(m -> m.contains(REFUSAL_FRAGMENT));
    assertThat(status.messages()).noneMatch(m -> m.contains("tk_secret"));
  }

  @Test
  void explicitOptOut_tokenOverPlainHttp_warnsButActivates() {
    NtfyLog4j2Appender appender = builder().setRequireHttpsForCredentials("false").build();

    appender.start();
    boolean started = appender.isStarted();
    appender.stop();

    assertThat(started).isTrue();
    assertThat(status.messages()).noneMatch(m -> m.contains(REFUSAL_FRAGMENT));
    assertThat(status.messages()).noneMatch(m -> m.contains("tk_secret"));
  }
}
