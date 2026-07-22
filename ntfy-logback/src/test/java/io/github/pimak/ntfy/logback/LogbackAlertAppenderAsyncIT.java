package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code setAsync(true)} makes {@code doAppend} non-blocking against a slow ntfy server (the
 * blocking send runs on the {@code ntfy-alert-delivery} daemon worker), that the alert still lands,
 * and that no delivery/http/digest thread survives {@code stop()}.
 */
@WireMockTest
class LogbackAlertAppenderAsyncIT {

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

  private static ILoggingEvent errorEvent(LoggerContext context, String message) {
    Logger logger = context.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderAsyncIT.class.getName(), logger, Level.ERROR, message, null, null);
  }

  @Test
  void asyncAppend_isNonBlockingAndDeliversWithoutLeakingThread(WireMockRuntimeInfo wm)
      throws InterruptedException {
    int delayMillis = 3000;
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(delayMillis)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setAsync(true);
    appender.setMaxAlertsPerWindow(10);
    appender.setSuppressionWindow("60000");
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    try {
      long start = System.nanoTime();
      appender.doAppend(errorEvent(context, "boom"));
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

      assertThat(elapsedMillis)
          .as("async doAppend must not block for the server response delay")
          .isLessThan(delayMillis / 2L);

      // The POST still lands once the slow stub responds.
      long deadline = System.currentTimeMillis() + delayMillis + 3000;
      boolean delivered = false;
      while (System.currentTimeMillis() < deadline) {
        try {
          verify(1, postRequestedFor(urlEqualTo("/alerts")));
          delivered = true;
          break;
        } catch (AssertionError notYet) {
          Thread.sleep(50);
        }
      }
      assertThat(delivered).isTrue();
    } finally {
      appender.stop();
    }

    assertThat(appender.isStarted()).isFalse();
    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-delivery thread should survive stop()")
        .isFalse();
  }
}
