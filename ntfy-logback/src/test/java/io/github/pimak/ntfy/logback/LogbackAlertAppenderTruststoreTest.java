package io.github.pimak.ntfy.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the transport XML setters reach the built {@link io.github.pimak.ntfy.core.NtfyConfig}.
 *
 * <p>An unloadable {@code <truststorePath>} is the observable that makes this testable without a
 * TLS server: core refuses activation for it, so a stopped appender plus the refusal diagnostic is
 * proof the value travelled from the setter all the way into the engine. The reverse case — setters
 * left untouched — must start normally, which is the non-regression guard for every existing user.
 */
class LogbackAlertAppenderTruststoreTest {

  private static LogbackAlertAppender appender(LoggerContext context) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    // Never contacted: nothing here appends, and the truststore case refuses before any HTTP
    // resource is acquired.
    appender.setUrl("https://localhost:1");
    appender.setTopic("alerts");
    return appender;
  }

  @Test
  void truststorePathSetter_reachesTheEngine_andAnUnloadableStoreRefusesActivation() {
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context);
    appender.setTruststorePath("/nonexistent/corp-ca.p12");

    appender.start();
    try {
      assertThat(appender.isStarted()).isFalse();
      List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
      assertThat(statuses)
          .extracting(Status::getMessage)
          .anyMatch(m -> m.contains("truststore could not be loaded"));
    } finally {
      appender.stop();
    }
  }

  @Test
  void truststoreDiagnostic_neverEchoesThePathOrPassword() {
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context);
    appender.setTruststorePath("/very/secret/location/corp-ca.p12");
    appender.setTruststorePassword("hunter2");

    appender.start();
    try {
      assertThat(context.getStatusManager().getCopyOfStatusList())
          .extracting(Status::getMessage)
          .noneMatch(m -> m.contains("hunter2") || m.contains("/very/secret/location"));
    } finally {
      appender.stop();
    }
  }

  @Test
  void proxySetter_reachesTheEngine_andAMalformedValueWarnsWithoutBlockingActivation() {
    // Opposite policy to the trust store: a mistyped route hint must not silence alerting.
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context);
    appender.setProxy("proxy.corp.example.com");  // no port

    appender.start();
    try {
      assertThat(appender.isStarted()).isTrue();
      assertThat(context.getStatusManager().getCopyOfStatusList())
          .extracting(Status::getMessage)
          .anyMatch(m -> m.contains("proxy must be"));
    } finally {
      appender.stop();
    }
  }

  @Test
  void transportSettersUntouched_appenderStartsNormally() {
    // The non-regression guard: none of this is opt-out, so an existing configuration that names
    // none of these keys must behave exactly as it did before they existed.
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context);

    appender.start();
    try {
      assertThat(appender.isStarted()).isTrue();
      assertThat(context.getStatusManager().getCopyOfStatusList())
          .extracting(Status::getMessage)
          .noneMatch(m -> m.contains("truststore") || m.contains("proxy must be"));
    } finally {
      appender.stop();
    }
  }
}
