package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
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
 * Asynchronous delivery — the default since 2.0, so these are the paths an ordinary deployment
 * actually takes. {@code ntfy-core}'s {@code AlertEngineAsyncDeliveryTest} already covers the
 * engine's own queue semantics (overflow, worker failure, capacity clamping); what is proven here
 * is the part the ADAPTER owns and core cannot see:
 *
 * <ul>
 *   <li>{@code doAppend} returns without waiting for the HTTP exchange, and the alert still lands;
 *   <li>the appender's {@code stop()} override drains queued-but-unsent alerts into the shutdown
 *       digest before tearing the engine down — get that ordering wrong and alerts are silently
 *       lost on shutdown;
 *   <li>the appender-owned {@link io.github.pimak.ntfy.core.PipelineCounters} still tally when the
 *       increments happen on the delivery worker rather than the logging thread.
 * </ul>
 *
 * <p>The Log4j2 sibling of this class carries one further test, for a recycled {@code LogEvent}
 * failing to corrupt an already-queued alert. It has no counterpart here on purpose: Logback
 * allocates a fresh {@code LoggingEvent} per call rather than pooling and reusing them, so the
 * hazard that test guards against does not exist on this adapter.
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
    // setAsync deliberately untouched: non-blocking delivery must be the default since 2.0.
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

  @Test
  void asyncStop_foldsQueuedButUnsentAlertsIntoTheShutdownDigest(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // Every response hangs well past the stop() timeout, so nothing the worker picks up can
    // complete: whatever is still queued or in flight when stop() runs is what must be accounted
    // for rather than dropped.
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(30_000)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    // Allowance high enough that the rate limiter suppresses nothing: the digest below can only be
    // describing alerts lost to the shutdown, which is the point of the test.
    appender.setMaxAlertsPerWindow(10);
    // Long window, so no scheduled digest can fire before stop() does the flushing.
    appender.setSuppressionWindow("60000");
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < 4; i++) {
      appender.doAppend(errorEvent(context, "boom " + i));
    }

    appender.stop();

    // The count must never be lost: the four alerts are either delivered or folded into a digest,
    // and since the stub never answers in time, all four end up in the digest.
    verify(
        postRequestedFor(urlEqualTo("/alerts"))
            .withRequestBody(containing("4 errors suppressed")));

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no engine thread should survive stop() in async mode")
        .isFalse();
  }

  @Test
  void asyncDelivery_stillTalliesTheAppenderOwnedCounters(WireMockRuntimeInfo wm)
      throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LoggerContext context = new LoggerContext();
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(context);
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setMaxAlertsPerWindow(10);
    appender.setSuppressionWindow("60000");
    appender.start();

    try {
      for (int i = 0; i < 3; i++) {
        appender.doAppend(errorEvent(context, "boom " + i));
      }

      // The increments happen on the delivery worker, not the logging thread, so the tally is only
      // observable after the queue drains.
      long deadline = System.currentTimeMillis() + 5000;
      while (appender.getCounters().published() < 3 && System.currentTimeMillis() < deadline) {
        Thread.sleep(25);
      }

      assertThat(appender.getCounters().published()).isEqualTo(3);
      assertThat(appender.getCounters().suppressed()).isZero();
      assertThat(appender.getCounters().failed()).isZero();
    } finally {
      appender.stop();
    }
  }
}
