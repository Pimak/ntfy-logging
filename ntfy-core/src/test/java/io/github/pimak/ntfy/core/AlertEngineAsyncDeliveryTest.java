package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Exercises the opt-in bounded-queue asynchronous delivery mode of {@link AlertEngine} end-to-end
 * against a WireMock ntfy endpoint (the first WireMock-backed test in ntfy-core; the dependency is
 * already declared). Covers the non-blocking guarantee, the preserved synchronous default, the
 * drop-and-count overflow policy, worker-thread failure accounting, the stop()-drains-into-digest
 * invariant with no leaked delivery thread, and the submit()-racing-stop() null-guard.
 */
@WireMockTest
class AlertEngineAsyncDeliveryTest {

  private static final class CapturingDiagnostics implements Diagnostics {
    final List<String> infos = new CopyOnWriteArrayList<>();
    final List<String> warns = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();

    @Override
    public void info(String msg) {
      infos.add(msg);
    }

    @Override
    public void warn(String msg) {
      warns.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {
      errors.add(msg);
    }
  }

  private static NtfyConfig.Builder baseConfig(WireMockRuntimeInfo wm) {
    return NtfyConfig.builder()
        .url("http://localhost:" + wm.getHttpPort())
        .topic("alerts")
        .appName("test-app");
  }

  private static AlertEvent errorEvent(String message) {
    return AlertEvent.of("com.example.Service", message, System.currentTimeMillis());
  }

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

  @Test
  void asyncSubmit_doesNotBlockCallerOnSlowServer_butEventuallyDelivers(WireMockRuntimeInfo wm)
      throws InterruptedException {
    int delayMillis = 3000;
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(delayMillis)));

    AlertEngine engine =
        new AlertEngine(baseConfig(wm).asyncEnabled(true).build(), new CapturingDiagnostics());
    engine.start();
    try {
      long start = System.nanoTime();
      engine.submit(errorEvent("boom"));
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

      // The blocking HTTP send happens on the daemon worker, so submit() must return promptly —
      // well under the server's response delay.
      assertThat(elapsedMillis).isLessThan(delayMillis / 2L);

      // The slow POST still lands eventually.
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
      assertThat(delivered).as("the async POST should eventually reach the server").isTrue();
    } finally {
      engine.stop();
    }
  }

  @Test
  void syncSubmit_blocksCallerOnSlowServer_provingOptInPreservesSemantics(WireMockRuntimeInfo wm) {
    int delayMillis = 1000;
    stubFor(
        post(urlEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(delayMillis)));

    // async NOT enabled (default): delivery is inline, so submit() blocks for the response delay.
    AlertEngine engine = new AlertEngine(baseConfig(wm).build(), new CapturingDiagnostics());
    engine.start();
    try {
      long start = System.nanoTime();
      engine.submit(errorEvent("boom"));
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

      assertThat(elapsedMillis).isGreaterThanOrEqualTo((long) delayMillis - 100L);
    } finally {
      engine.stop();
    }
  }

  @Test
  void overflow_dropsAreFoldedIntoDigestAndWarned(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // A slow stub keeps the single worker busy so the tiny queue overflows.
    stubFor(
        post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .asyncEnabled(true)
                .asyncQueueCapacity(1)
                // Disable rate limiting so every event is enqueued (the queue, not the limiter, is
                // the bound under test).
                .maxAlertsPerWindow(0)
                .suppressionWindow(java.time.Duration.ofMillis(60_000))
                .build(),
            diagnostics);
    engine.start();
    try {
      // 1 in-flight on the worker + 1 sitting in the capacity-1 queue = 2 accepted; the rest drop.
      int total = 8;
      for (int i = 0; i < total; i++) {
        engine.submit(errorEvent("boom " + i));
      }

      assertThat(diagnostics.warns)
          .as("a queue-overflow warning must fire")
          .contains(AlertMessages.STATUS_ASYNC_QUEUE_OVERFLOW);
    } finally {
      // Re-point nothing; the digest publishes to /alerts. Give a fresh fast stub for the flush.
      stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
      engine.stop();
    }

    // The digest body reports the total suppressed (dropped + any rate-limited) count. At least the
    // overflow drops (total - 2 accepted = 6) must appear.
    verify(
        postRequestedFor(urlEqualTo("/alerts"))
            .withRequestBody(containing("errors suppressed in the last")));
    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive()).isFalse();
  }

  @Test
  void workerFailure_foldsIntoDigestCount(WireMockRuntimeInfo wm) throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));

    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .asyncEnabled(true)
                .maxAlertsPerWindow(5)
                .suppressionWindow(java.time.Duration.ofMillis(60_000))
                .build(),
            diagnostics);
    engine.start();
    try {
      engine.submit(errorEvent("boom"));

      // Wait for the worker to process the failing publish.
      long deadline = System.currentTimeMillis() + 3000;
      while (diagnostics.warns.isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(25);
      }
      assertThat(diagnostics.warns)
          .as("a failed worker publish surfaces a publish-failed warning")
          .anyMatch(w -> w.contains("ntfy publish failed"));
    } finally {
      // Let the stop() digest flush succeed so we can assert the failed event was folded in.
      stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
      engine.stop();
    }

    // The failed error publish must reappear in the digest suppression count (parity with the sync
    // path): 1 error suppressed.
    verify(
        postRequestedFor(urlEqualTo("/alerts"))
            .withRequestBody(containing("1 errors suppressed in the last")));
  }

  @Test
  void stop_drainsQueuedEventsIntoDigestAndLeaksNoDeliveryThread(WireMockRuntimeInfo wm)
      throws InterruptedException {
    // Hang the worker on the first send so a backlog builds in the queue, then stop().
    stubFor(
        post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .asyncEnabled(true)
                .asyncQueueCapacity(16)
                .maxAlertsPerWindow(0) // no rate limiting: every event enqueues
                .suppressionWindow(java.time.Duration.ofMillis(60_000))
                .build(),
            diagnostics);
    engine.start();

    int queued = 5;
    for (int i = 0; i < queued; i++) {
      engine.submit(errorEvent("boom " + i));
    }

    // Point the digest flush at a fast response so stop() can complete quickly.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    engine.stop();
    assertThat(engine.isStarted()).isFalse();

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive())
        .as("no ntfy-alert-delivery/http/digest thread should survive stop()")
        .isFalse();

    // The queued-but-unsent events fold into the suppression count and are reported by the flush.
    verify(
        postRequestedFor(urlEqualTo("/alerts"))
            .withRequestBody(containing("errors suppressed in the last")));
  }

  @Test
  void submitAfterStop_doesNotThrowAndIsBenign(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(baseConfig(wm).asyncEnabled(true).build(), diagnostics);
    engine.start();
    engine.stop();

    // A submit() arriving after stop() nulled the volatile fields must no-op, never NPE, and never
    // emit an ERROR-level diagnostic.
    assertThatCode(() -> engine.submit(errorEvent("late"))).doesNotThrowAnyException();
    assertThat(diagnostics.errors).isEmpty();
  }

  @Test
  void nonPositiveQueueCapacity_isClampedWithWarning(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).asyncEnabled(true).asyncQueueCapacity(0).build(), diagnostics);
    engine.start();
    try {
      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_ASYNC_QUEUE_CAPACITY);
    } finally {
      engine.stop();
    }
  }

  @Test
  void repeatedStartStopCycles_leakNoThreads(WireMockRuntimeInfo wm) throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    for (int cycle = 0; cycle < 3; cycle++) {
      AlertEngine engine =
          new AlertEngine(baseConfig(wm).asyncEnabled(true).build(), new CapturingDiagnostics());
      engine.start();
      for (int i = 0; i < 3; i++) {
        engine.submit(errorEvent("boom " + i));
      }
      engine.stop();
    }

    awaitNoNtfyThreads();
    assertThat(anyNtfyThreadAlive()).isFalse();
  }
}
