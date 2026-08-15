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

/**
 * Proves a burst above the allowance produces exactly one aggregated digest at window close with
 * {@code count == events - allowance}, that individual alerts carry the error priority/tags, and
 * that the digest carries the digest priority/tags.
 */
@WireMockTest
class NtfyLog4j2AppenderDigestIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_EVENTS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 7

  private static LogEvent errorEvent(String message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage(message))
        .build();
  }

  @Test
  void burstAboveAllowance_producesExactlyOneDigestWithSuppressedCount(WireMockRuntimeInfo wm)
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
            // Window comfortably longer than the burst's synchronous HTTP round-trips so the
            // allowance cannot refill mid-burst and leak a 4th individual publish.
            .setSuppressionWindow("2000") // 2 s
            .build();
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      appender.append(errorEvent("boom " + i));
    }

    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline
        && WireMock.findAll(postRequestedFor(urlEqualTo("/alerts"))).size()
            < MAX_ALERTS_PER_WINDOW + 1) {
      Thread.sleep(25);
    }

    appender.stop();

    verify(
        MAX_ALERTS_PER_WINDOW,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("high"))
            .withHeader("Tags", equalTo("rotating_light")));

    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(EXPECTED_SUPPRESSED + " errors suppressed")));
  }
}
