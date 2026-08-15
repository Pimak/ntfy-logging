package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

/**
 * Proves a failed digest publish carries the drained suppression count forward (via the engine's
 * internal rate-limiter {@code restore}) instead of dropping it — the next window's digest reports
 * the same suppressed total. A WireMock scenario fails only the FIRST digest POST (500). The
 * in-flight guard keys off the engine's {@code ntfy-alert-digest} thread name, unchanged in core.
 */
@WireMockTest
class NtfyLog4j2AppenderDigestRestoreIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 7
  private static final String DIGEST_THREAD_NAME = "ntfy-alert-digest";

  private static LogEvent errorEvent(String message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage(message))
        .build();
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
   * inside {@code HttpClient.send()} for the digest POST — proof the response has not yet been
   * fully consumed. Matches both the public {@code java.net.http} API package AND the JDK's actual
   * internal {@code jdk.internal.net.http} package.
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

    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.newBuilder()
            .setName("ntfy")
            .setUrl("http://localhost:" + wm.getHttpPort())
            .setTopic("alerts")
            // Pinned synchronous so the assertions below observe publish/suppress semantics
            // deterministically; the async default is covered by NtfyLog4j2AppenderAsyncIT.
            .setAsync("false")
            .setMaxAlertsPerWindow(String.valueOf(MAX_ALERTS_PER_WINDOW))
            .setSuppressionWindow("2000") // 2 s
            .build();
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.append(errorEvent("boom " + i));
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
