package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.impl.MutableLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * Asynchronous delivery — the default since 2.0, so these are the paths an ordinary deployment
 * actually takes. {@code ntfy-core}'s {@code AlertEngineAsyncDeliveryTest} already covers the
 * engine's own queue semantics (overflow, worker failure, capacity clamping); what is proven here
 * is the part the ADAPTER owns and core cannot see:
 *
 * <ul>
 *   <li>{@code append} returns without waiting for the HTTP exchange, and the alert still lands;
 *   <li>the appender's {@code stop(long, TimeUnit)} override drains queued-but-unsent alerts into
 *       the shutdown digest before tearing the engine down — get that ordering wrong and alerts
 *       are silently lost on shutdown;
 *   <li>the appender-owned {@link io.github.pimak.ntfy.core.PipelineCounters} still tally when the
 *       increments happen on the delivery worker rather than the logging thread;
 *   <li>a recycled {@code LogEvent} cannot corrupt an already-queued alert.
 * </ul>
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

  @Test
  void asyncStop_foldsQueuedButUnsentAlertsIntoTheShutdownDigest(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // Every response hangs well past the stop() timeout, so nothing the worker picks up can
    // complete: whatever is still queued or in flight when stop() runs is what must be accounted
    // for rather than dropped.
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(30_000)));

    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.newBuilder()
            .setName("ntfy")
            .setUrl("http://localhost:" + wm.getHttpPort())
            .setTopic("alerts")
            .setAsync("true")
            // Allowance high enough that the rate limiter suppresses nothing: the digest below can
            // only be describing alerts lost to the shutdown, which is the point of the test.
            .setMaxAlertsPerWindow("10")
            // Long window, so no scheduled digest can fire before stop() does the flushing.
            .setSuppressionWindow("60000")
            .build();
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    for (int i = 0; i < 4; i++) {
      appender.append(errorEvent("boom " + i));
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

    try {
      for (int i = 0; i < 3; i++) {
        appender.append(errorEvent("boom " + i));
      }

      // The increments happen on the delivery worker, not the logging thread, so the tally is
      // only observable after the queue drains.
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

  @Test
  void asyncAppend_recycledLogEventCannotCorruptTheQueuedAlert(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // The reason Log4j2EventMapper snapshots eagerly inside append(). Log4j2 reuses MutableLogEvent
    // instances, and in async mode the alert is rendered long after append() returned — so a
    // recycled event would rewrite an already-queued notification if anything were held by
    // reference. Delaying the response keeps the alert in flight across the mutation.
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(500)));

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

    try {
      MutableLogEvent reused = new MutableLogEvent();
      reused.setLoggerName("com.example.testapp.SimulatedConsumer");
      reused.setLevel(Level.ERROR);
      reused.setMessage(new SimpleMessage("original message"));

      appender.append(reused);

      // Exactly what Log4j2 itself does to a pooled event before handing it to the next caller.
      reused.clear();
      reused.setLoggerName("com.example.testapp.SomethingElse");
      reused.setLevel(Level.ERROR);
      reused.setMessage(new SimpleMessage("recycled message"));

      long deadline = System.currentTimeMillis() + 5000;
      boolean delivered = false;
      while (System.currentTimeMillis() < deadline) {
        try {
          verify(1, postRequestedFor(urlEqualTo("/alerts")));
          delivered = true;
          break;
        } catch (AssertionError notYet) {
          Thread.sleep(25);
        }
      }
      assertThat(delivered).isTrue();

      verify(
          postRequestedFor(urlEqualTo("/alerts"))
              .withRequestBody(containing("original message")));
      verify(
          0,
          postRequestedFor(urlEqualTo("/alerts"))
              .withRequestBody(containing("recycled message")));
    } finally {
      appender.stop();
    }
  }
}
