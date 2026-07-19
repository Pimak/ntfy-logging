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

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Proves a failed digest publish carries the drained suppression count forward (via the engine's
 * internal rate-limiter {@code restore}) instead of dropping it — the next window's digest reports
 * the same suppressed total. A WireMock scenario fails only the FIRST digest POST (500). Ported from
 * the original {@code NtfyAlertAppenderDigestRestoreIT} against the thin appender; the in-flight
 * guard keys off the engine's {@code ntfy-alert-digest} thread name, unchanged in core.
 */
@WireMockTest
class LogbackAlertAppenderDigestRestoreIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 7
  private static final String DIGEST_THREAD_NAME = "ntfy-alert-digest";

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderDigestRestoreIT.class.getName(),
        logger,
        Level.ERROR,
        message,
        null,
        null);
  }

  private static Thread findThreadByName(String name) {
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (name.equals(t.getName())) {
        return t;
      }
    }
    return null;
  }

  /**
   * True while {@code thread}'s current stack still shows an HTTP-client frame, i.e. it is still
   * inside {@code HttpClient.send()} for the digest POST — proof the response has not yet been fully
   * consumed. Matches both the public {@code java.net.http} API package AND the JDK's actual internal
   * {@code jdk.internal.net.http} package.
   */
  private static boolean httpClientStillInFlight(Thread thread) {
    if (thread == null || !thread.isAlive()) {
      return false;
    }
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().contains(".net.http.")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void failedDigestPublish_carriesSuppressedCountIntoNextWindowsDigest(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(
        post(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("high"))
            .willReturn(aResponse().withStatus(200)));
    stubFor(
        post(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .inScenario("digest-restore")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("first-digest-failed"));
    stubFor(
        post(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .inScenario("digest-restore")
            .whenScenarioStateIs("first-digest-failed")
            .willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(MAX_ALERTS_PER_WINDOW);
    appender.setSuppressionWindow("2000"); // 2 s
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline
        && WireMock.findAll(
                    postRequestedFor(urlEqualTo("/alerts"))
                        .withHeader("Priority", equalTo("urgent")))
                .size()
            < 2) {
      Thread.sleep(25);
    }

    // Wait until the digest thread's stack has left the HTTP client implementation before stop(),
    // so shutdownNow()'s interrupt cannot race an in-progress send() into a spurious THIRD flush.
    long consumedDeadline = System.currentTimeMillis() + 5_000;
    Thread digestThread = findThreadByName(DIGEST_THREAD_NAME);
    while (System.currentTimeMillis() < consumedDeadline && httpClientStillInFlight(digestThread)) {
      Thread.sleep(5);
    }

    appender.stop();

    verify(
        2,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(EXPECTED_SUPPRESSED + " errors suppressed")));
    verify(
        2,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withRequestBody(containing("com.example.testapp.SimulatedConsumer")));
  }
}
