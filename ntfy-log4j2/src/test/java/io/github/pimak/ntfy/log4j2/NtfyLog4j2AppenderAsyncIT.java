package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
 * Proves {@code async="true"} makes {@code append} non-blocking against a slow ntfy server (the
 * blocking send runs on the {@code ntfy-alert-delivery} daemon worker), that the alert still lands,
 * and that no delivery/http/digest thread survives {@code stop()}.
 */
@WireMockTest
class NtfyLog4j2AppenderAsyncIT {

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
  void asyncAppend_isNonBlockingAndDeliversWithoutLeakingThread(WireMockRuntimeInfo wm)
      throws InterruptedException {
    int delayMillis = 3000;
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(delayMillis)));

    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.newBuilder()
            .setName("ntfy")
            .setUrl("http://localhost:" + wm.getHttpPort())
            .setTopic("alerts")
            .setAsync("true")
            .setMaxAlertsPerWindow("10")
            .setSuppressionWindow("60000")
            .build();
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    try {
      long start = System.nanoTime();
      appender.append(errorEvent("boom"));
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

      assertThat(elapsedMillis)
          .as("async append must not block for the server response delay")
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
