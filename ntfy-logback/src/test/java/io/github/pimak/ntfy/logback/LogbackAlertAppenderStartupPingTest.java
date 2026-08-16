package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code <startupPing>} / {@code <startupPingFailFast>} XML setters reach the built
 * {@link io.github.pimak.ntfy.core.NtfyConfig} and take effect, and that leaving them untouched
 * keeps the appender's behaviour and status output exactly as before the feature existed.
 */
@WireMockTest
class LogbackAlertAppenderStartupPingTest {

  private static LogbackAlertAppender appender(LoggerContext context, WireMockRuntimeInfo wm) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    return appender;
  }

  private static List<String> statuses(LoggerContext context) {
    return context.getStatusManager().getCopyOfStatusList().stream().map(Status::getMessage).toList();
  }

  private static void awaitSelfTestStatus(LoggerContext context) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3000;
    while (statuses(context).stream().noneMatch(m -> m.contains("startup self-test"))
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
  }

  @Test
  void untouched_noSelfTestRunsAndNoStatusIsAdded(WireMockRuntimeInfo wm) throws Exception {
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context, wm);

    appender.start();
    Thread.sleep(200);
    appender.stop();

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(statuses(context)).noneMatch(m -> m.contains("startup self-test"));
  }

  @Test
  void startupPingProbe_reachesTheEngineAndReportsOnTheStatusManager(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context, wm);
    appender.setStartupPing("probe");

    appender.start();
    awaitSelfTestStatus(context);
    appender.stop();

    verify(1, getRequestedFor(urlPathEqualTo("/alerts/json")));
    assertThat(statuses(context)).anyMatch(m -> m.contains("startup self-test PASSED (probe)"));
  }

  @Test
  void aFailedSelfTestIsDiagnosedButStillLeavesTheAppenderRunning(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(401)));
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context, wm);
    appender.setStartupPing("probe");

    appender.start();
    awaitSelfTestStatus(context);

    // A logging appender must never take the application down by default.
    assertThat(appender.isStarted()).isTrue();
    assertThat(statuses(context))
        .anyMatch(m -> m.contains("401") && m.contains("revoked or expired"));
    appender.stop();
  }

  @Test
  void anUnrecognizedModeIsWarnedAboutRatherThanSilentlyTreatedAsOff(WireMockRuntimeInfo wm)
      throws Exception {
    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context, wm);
    appender.setStartupPing("prob");

    appender.start();
    Thread.sleep(150);
    appender.stop();

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(statuses(context)).anyMatch(m -> m.contains("not a recognized mode"));
  }
}
