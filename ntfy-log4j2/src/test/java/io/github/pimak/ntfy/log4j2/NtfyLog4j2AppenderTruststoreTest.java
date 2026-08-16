package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the transport plugin attributes reach the built {@link
 * io.github.pimak.ntfy.core.NtfyConfig}.
 *
 * <p>An unloadable {@code truststorePath} is what makes this observable without a TLS server: core
 * refuses activation for it, so an appender that never reports itself started — plus the refusal on
 * the StatusLogger — is proof the attribute travelled from {@code log4j2.xml} into the engine.
 */
class NtfyLog4j2AppenderTruststoreTest {

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
        // Never contacted: nothing here appends, and the truststore case refuses before any HTTP
        // resource is acquired.
        .setUrl("https://localhost:1")
        .setTopic("alerts");
  }

  @Test
  void truststorePathAttribute_reachesTheEngine_andAnUnloadableStoreRefusesActivation() {
    NtfyLog4j2Appender appender =
        builder().setTruststorePath("/nonexistent/corp-ca.p12").build();

    appender.start();
    appender.stop();

    assertThat(appender.isStarted()).isFalse();
    assertThat(status.messages()).anyMatch(m -> m.contains("truststore could not be loaded"));
  }

  @Test
  void truststoreDiagnostic_neverEchoesThePathOrPassword() {
    NtfyLog4j2Appender appender = builder()
        .setTruststorePath("/very/secret/location/corp-ca.p12")
        .setTruststorePassword("hunter2")
        .build();

    appender.start();
    appender.stop();

    assertThat(status.messages())
        .noneMatch(m -> m.contains("hunter2") || m.contains("/very/secret/location"));
  }

  @Test
  void proxyAttribute_reachesTheEngine_andAMalformedValueWarnsWithoutBlockingActivation() {
    // Opposite policy to the trust store: a mistyped route hint must not silence alerting.
    NtfyLog4j2Appender appender = builder().setProxy("proxy.corp.example.com").build();

    appender.start();
    try {
      assertThat(appender.isStarted()).isTrue();
      assertThat(status.messages()).anyMatch(m -> m.contains("proxy must be"));
    } finally {
      appender.stop();
    }
  }

  @Test
  void transportAttributesUntouched_appenderStartsNormally() {
    // The non-regression guard: an existing log4j2.xml naming none of these attributes must behave
    // exactly as it did before they existed.
    NtfyLog4j2Appender appender = builder().build();

    appender.start();
    try {
      assertThat(appender.isStarted()).isTrue();
      assertThat(status.messages())
          .noneMatch(m -> m.contains("truststore") || m.contains("proxy must be"));
    } finally {
      appender.stop();
    }
  }
}
