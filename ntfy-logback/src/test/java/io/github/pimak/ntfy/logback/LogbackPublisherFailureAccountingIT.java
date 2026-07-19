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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Proves a failed individual publish (simulated 500) folds into the suppression count instead of
 * being lost, surfacing later in the aggregated digest. Ported from the original {@code
 * NtfyPublisherFailureAccountingIT} against the thin appender.
 */
@WireMockTest
class LogbackPublisherFailureAccountingIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackPublisherFailureAccountingIT.class.getName(),
        logger,
        Level.ERROR,
        message,
        null,
        null);
  }

  @Test
  void failedIndividualPublish_foldsIntoDigestCount(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // Every individual publish attempt fails (500) — all within the allowance, so each attempts to
    // publish and fails, folding into the suppression count.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(MAX_ALERTS_PER_WINDOW);
    appender.setSuppressionWindow("300"); // 300 ms
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < MAX_ALERTS_PER_WINDOW; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    // After the individual attempts land, switch the stub to succeed so the digest publish itself
    // goes through (the digest also POSTs to /alerts).
    long attemptDeadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < attemptDeadline
        && WireMock.findAll(postRequestedFor(urlEqualTo("/alerts"))).size() < MAX_ALERTS_PER_WINDOW) {
      Thread.sleep(25);
    }
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline
        && WireMock.findAll(
                postRequestedFor(urlEqualTo("/alerts")).withHeader("Priority", equalTo("urgent")))
            .isEmpty()) {
      Thread.sleep(25);
    }

    appender.stop();

    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("urgent"))
            .withHeader("Tags", equalTo("fire"))
            .withRequestBody(containing(MAX_ALERTS_PER_WINDOW + " errors suppressed")));
  }
}
