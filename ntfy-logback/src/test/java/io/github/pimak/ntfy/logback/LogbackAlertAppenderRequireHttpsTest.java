package io.github.pimak.ntfy.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code <requireHttpsForCredentials>} XML setter reaches the built {@link
 * io.github.pimak.ntfy.core.NtfyConfig}: with strict mode on and a token over a plain {@code
 * http://} URL, the engine refuses activation (fixed refusal diagnostic, no publish attempt);
 * with the setter untouched the default warn-and-activate behavior holds.
 */
class LogbackAlertAppenderRequireHttpsTest {

  private static LogbackAlertAppender appender(LoggerContext context) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    // Never contacted: strict mode refuses before any HTTP resource is acquired, and the
    // default-mode test only starts/stops without appending.
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    appender.setToken("tk_secret");
    return appender;
  }

  @Test
  void strictMode_tokenOverPlainHttp_refusesActivation() {
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context);
    appender.setRequireHttpsForCredentials(true);

    appender.start();
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .anyMatch(m -> m.contains("require-https-for-credentials is enabled"));
  }

  @Test
  void defaultMode_tokenOverPlainHttp_warnsButActivates() {
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context);

    appender.start();
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses)
        .extracting(Status::getMessage)
        .noneMatch(m -> m.contains("require-https-for-credentials is enabled"));
  }
}
