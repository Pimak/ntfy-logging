package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Proves {@code stop()} flushes exactly one pending digest synchronously — before the digest timer
 * would ever fire naturally — and that no {@code ntfy-alert-http}/{@code ntfy-alert-digest} thread
 * survives. Ported from the original {@code NtfyAlertAppenderStopFlushIT} against the thin appender.
 */
@WireMockTest
class LogbackAlertAppenderStopFlushIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 6;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 3

  private static boolean anyNtfyThreadAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(
            t ->
                t.isAlive()
                    && (t.getName().startsWith("ntfy-alert-http")
                        || t.getName().startsWith("ntfy-alert-digest")
                        || (t.getName().contains("HttpClient-")
                            && t.getName().contains("SelectorManager"))));
  }

  private static void awaitNoNtfyThreads() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (anyNtfyThreadAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderStopFlushIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  @Test
  void stop_beforeWindowElapses_flushesExactlyOneDigestAndLeaksNoThread(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(MAX_ALERTS_PER_WINDOW);
    // Long window: the digest timer must never fire naturally during this test.
    appender.setSuppressionWindow("60000"); // 60 s
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-http or ntfy-alert-digest thread should survive stop()")
        .isFalse();

    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(EXPECTED_SUPPRESSED + " errors suppressed")));

    verify(
        MAX_ALERTS_PER_WINDOW,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("high"))
            .withHeader("Tags", equalTo("rotating_light")));
  }
}
