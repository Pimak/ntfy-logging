package io.github.pimak.ntfy.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code <includeMdcKeys>} XML setter reaches the built {@link NtfyConfig} and, from
 * there, the allow-list the appender projects with:
 * the raw CSV is split on commas and each key trimmed, in the configured order, and an unset setter
 * leaves the allow-list empty so the MDC is never consulted at all.
 */
class LogbackAlertAppenderIncludeMdcKeysTest {

  private static LogbackAlertAppender appender() {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    // Never contacted: these tests only start/stop the appender, they never append an event.
    appender.setUrl("http://localhost:1");
    appender.setTopic("alerts");
    return appender;
  }

  @Test
  void includeMdcKeysSetter_isSplitTrimmedAndReachesTheConfig() {
    LogbackAlertAppender appender = appender();
    appender.setIncludeMdcKeys("correlation-id,  tenant ,,  user-id");

    appender.start();

    // Order is the configured order (that is the body's rendering order), whitespace around each
    // key is trimmed, and the empty run between the two commas contributes nothing.
    assertThat(appender.resolvedMdcKeys()).containsExactly("correlation-id", "tenant", "user-id");
    appender.stop();
  }

  @Test
  void unsetIncludeMdcKeys_leavesTheAllowListEmpty() {
    LogbackAlertAppender appender = appender();

    appender.start();

    // The default must stay opt-in: no key named, so no MDC value can ever be read.
    assertThat(appender.resolvedMdcKeys()).isEmpty();
    appender.stop();
  }

  @Test
  void stop_clearsTheResolvedAllowList() {
    LogbackAlertAppender appender = appender();
    appender.setIncludeMdcKeys("tenant");

    appender.start();
    appender.stop();

    // A stopped appender holds no resolved configuration; a restart re-resolves it from scratch.
    assertThat(appender.resolvedMdcKeys()).isEmpty();
  }

  @Test
  void injectedConfig_suppliesTheAllowList() {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setConfig(
        NtfyConfig.builder()
            .url("http://localhost:1")
            .topic("alerts")
            .includeMdcKeys(List.of("tenant"))
            .build());

    appender.start();

    // The allow-list is read from the BUILT config, so the auto-install path (which never touches
    // a JavaBean setter) resolves its keys exactly like the XML path does.
    assertThat(appender.resolvedMdcKeys()).containsExactly("tenant");
    appender.stop();
  }
}
