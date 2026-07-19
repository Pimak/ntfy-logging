package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Simulates Spring Boot's documented double {@code LoggerContext} init (start/stop/start/stop) and
 * proves the engine's {@code ntfy-alert-http} daemon executor never survives a {@code stop()}, and
 * that a single {@code append()} between the two cycles produces exactly one outbound HTTP call.
 * Ported from the original {@code NtfyAlertAppenderDoubleResetTest} against the thin appender.
 */
@WireMockTest
class LogbackAlertAppenderDoubleResetTest {

  // Scans both the engine's own executor threads AND the JDK HttpClient's internal selector-manager
  // thread: stop() must release both deterministically.
  private static boolean anyNtfyThreadAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(
            t ->
                t.isAlive()
                    && (t.getName().startsWith("ntfy-alert-http")
                        || (t.getName().contains("HttpClient-")
                            && t.getName().contains("SelectorManager"))));
  }

  private static void awaitNoNtfyThreads() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (anyNtfyThreadAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  private static LoggingEvent consumerEvent() {
    // Simulated downstream-consumer logger name, NOT under io.github.pimak.ntfy, so it is unaffected
    // by the self-package exclusion — this test exercises lifecycle reset, not the exclusion gate.
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderDoubleResetTest.class.getName(),
        logger,
        Level.ERROR,
        "boom",
        null,
        null);
  }

  @Test
  void twoStartStopCycles_leakNoThreadAndSendExactlyOneHttpCall(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");

    appender.start();
    assertThat(appender.isStarted()).isTrue();
    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-http thread should survive the first stop()")
        .isFalse();

    appender.start();
    assertThat(appender.isStarted()).isTrue();

    appender.doAppend(consumerEvent());

    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-http thread should survive the second stop()")
        .isFalse();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }

  @Test
  void doubleStartWithoutStop_doesNotLeakThreadsOrDuplicatePublish(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");

    appender.start();
    assertThat(appender.isStarted()).isTrue();

    // Publish once so the first executor/HttpClient actually spin up their threads.
    appender.doAppend(consumerEvent());

    // Second start() with no stop() in between: must be a guarded no-op.
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    appender.stop();
    assertThat(appender.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("a single stop() must release every thread even after a double start()")
        .isFalse();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }
}
