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

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * Proves {@code stop()} flushes exactly one pending digest synchronously — before the digest timer
 * would ever fire naturally — and that no {@code ntfy-alert-http}/{@code ntfy-alert-digest} thread
 * survives. log4j2 routes the no-argument {@code stop()} through {@code stop(long, TimeUnit)}, so
 * this also pins that the engine teardown really sits on the single overridden path.
 */
@WireMockTest
class NtfyLog4j2AppenderStopFlushIT {

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
                        || t.getName().startsWith("ntfy-alert-delivery")
                        || (t.getName().contains("HttpClient-")
                            && t.getName().contains("SelectorManager"))));
  }

  private static void awaitNoNtfyThreads() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (anyNtfyThreadAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  private static LogEvent errorEvent(String message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage(message))
        .build();
  }

  @Test
  void stop_beforeWindowElapses_flushesExactlyOneDigestAndLeaksNoThread(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.newBuilder()
            .setName("ntfy")
            .setUrl("http://localhost:" + wm.getHttpPort())
            .setTopic("alerts")
            // Pinned synchronous so the assertions below observe publish/suppress semantics
            // deterministically; the async default is covered by NtfyLog4j2AppenderAsyncIT.
            .setAsync("false")
            .setMaxAlertsPerWindow(String.valueOf(MAX_ALERTS_PER_WINDOW))
            // Long window: the digest timer must never fire naturally during this test.
            .setSuppressionWindow("60000") // 60 s
            .build();
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.append(errorEvent("boom " + i));
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
