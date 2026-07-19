package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;

/**
 * Proves the appender's failure path (routed through the engine's {@link LogbackDiagnostics}) reports
 * exclusively through the inherited StatusManager methods — never an SLF4J logger, so zero recursive
 * {@code ILoggingEvent}s are generated — and never leaks the configured token into any status entry,
 * even on a simulated 401/403 ntfy response. Ported from the original {@code
 * NtfyAlertAppenderStatusManagerTest} against the thin appender.
 */
@WireMockTest
class LogbackAlertAppenderStatusManagerTest {

  private static final String SECRET_TOKEN = "SECRET-TOKEN-123";

  private static LogbackAlertAppender startedAppender(LoggerContext context, int wireMockPort) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    appender.setToken(SECRET_TOKEN);
    appender.start();
    return appender;
  }

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    // Simulated downstream-consumer logger name: NOT under io.github.pimak.ntfy, so the self-package
    // exclusion does not gate the event out before it reaches the publish path under test.
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderStatusManagerTest.class.getName(),
        logger,
        Level.ERROR,
        message,
        null,
        null);
  }

  @Test
  void append_serverReturns401_reportsFailureViaStatusManagerNeverLeakingToken(
      WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(401)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = startedAppender(context, wm.getHttpPort());

    appender.doAppend(errorEvent(context, "database connection failed"));
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }

  @Test
  void append_serverReturns403_reportsFailureViaStatusManagerNeverLeakingToken(
      WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(403)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = startedAppender(context, wm.getHttpPort());

    appender.doAppend(errorEvent(context, "database connection failed"));
    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }

  @Test
  void append_publishFailure_generatesNoRecursiveLoggingEvent(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));

    LoggerContext context = new LoggerContext();
    context.start();

    ListAppender<ILoggingEvent> capture = new ListAppender<>();
    capture.setContext(context);
    capture.start();

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setToken(SECRET_TOKEN);
    appender.start();

    Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(Level.ERROR);
    // Both appenders on the same logger, dispatched by a single SLF4J call: if the failure path
    // ever called an SLF4J logger (the recursion trap this test guards), that second call would
    // also route through this logger and land in `capture` too.
    rootLogger.addAppender(capture);
    rootLogger.addAppender(appender);

    rootLogger.error("boom");

    appender.stop();

    assertThat(capture.list).hasSize(1);

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }

  /**
   * Regression coverage: a burst above the allowance triggers the digest-publish path (and its
   * diagnostics) in addition to the individual-alert path; neither may ever leak the configured
   * token into a StatusManager message.
   */
  @Test
  void startBurstStop_noStatusManagerMessageEverContainsTheToken(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setToken(SECRET_TOKEN);
    appender.setMaxAlertsPerWindow(2);
    appender.setSuppressionWindow("300"); // 300 ms
    appender.start();

    for (int i = 0; i < 5; i++) {
      appender.doAppend(errorEvent(context, "burst " + i));
    }

    // Give the digest timer a chance to fire before stop()'s own flush runs.
    Thread.sleep(400);

    appender.stop();

    List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
    assertThat(statuses).isNotEmpty();
    assertThat(statuses).extracting(Status::getMessage).noneMatch(m -> m.contains(SECRET_TOKEN));
  }
}
