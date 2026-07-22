package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.pimak.ntfy.core.PipelineCounters;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Proves {@link LogbackAlertAppender#getCounters()} exposes correct pipeline tallies and that the
 * counters are owned by the appender: the same {@link PipelineCounters} instance survives a {@code
 * stop()}/{@code start()} cycle (a new engine, same holder) and keeps accumulating monotonically.
 */
@WireMockTest
class LogbackPipelineCountersIT {

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackPipelineCountersIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  private static LogbackAlertAppender appender(LoggerContext context, WireMockRuntimeInfo wm,
      int maxAlertsPerWindow) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(maxAlertsPerWindow);
    appender.setSuppressionWindow("60000"); // never fires naturally during the test
    return appender;
  }

  @Test
  void counters_reflectMixOfPublishedAndSuppressed(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context, wm, 2);
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    // 2 within allowance publish, 2 over-allowance suppressed.
    for (int i = 0; i < 4; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    assertThat(appender.getCounters().published()).isEqualTo(2);
    assertThat(appender.getCounters().suppressed()).isEqualTo(2);
    assertThat(appender.getCounters().failed()).isZero();

    appender.stop();
  }

  @Test
  void counters_surviveStopStartCycle_monotonic(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = appender(context, wm, 10);
    appender.start();

    PipelineCounters countersBefore = appender.getCounters();
    for (int i = 0; i < 3; i++) {
      appender.doAppend(errorEvent(context, "first-cycle " + i));
    }
    assertThat(appender.getCounters().published()).isEqualTo(3);

    // A stop/start cycle builds a brand-new engine but must reuse the appender-owned counters.
    appender.stop();
    appender.start();
    assertThat(appender.isStarted()).isTrue();
    assertThat(appender.getCounters())
        .as("same PipelineCounters instance across the stop/start cycle")
        .isSameAs(countersBefore);

    for (int i = 0; i < 2; i++) {
      appender.doAppend(errorEvent(context, "second-cycle " + i));
    }

    // 3 from the first engine + 2 from the second — accumulated, not reset.
    assertThat(appender.getCounters().published()).isEqualTo(5);
    assertThat(appender.getCounters().suppressed()).isZero();
    assertThat(appender.getCounters().failed()).isZero();

    appender.stop();
  }
}
