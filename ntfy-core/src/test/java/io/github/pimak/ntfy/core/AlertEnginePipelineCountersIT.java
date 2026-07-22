package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;

/**
 * Drives an {@link AlertEngine} against a loopback WireMock server and asserts the observability
 * counters (published / suppressed / failed) are correct and, crucially, <em>disjoint</em>: a
 * rate-limited event only ever counts {@code suppressed}, and a failed publish only ever counts
 * {@code failed} — the {@code recordSuppressed} digest fold on a failed individual publish never
 * leaks into the {@code suppressed} observability counter.
 *
 * <p>Individual publishes and the {@code stop()} digest flush both run synchronously on the calling
 * thread, so the counts are deterministic without any timer wait.
 */
@WireMockTest
class AlertEnginePipelineCountersIT {

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static NtfyConfig config(
      WireMockRuntimeInfo wm, int maxAlertsPerWindow, long suppressionWindowMillis) {
    return NtfyConfig.builder()
        .url("http://localhost:" + wm.getHttpPort())
        .topic("alerts")
        .maxAlertsPerWindow(maxAlertsPerWindow)
        .suppressionWindow(java.time.Duration.ofMillis(suppressionWindowMillis))
        .build();
  }

  private static AlertEngine engine(WireMockRuntimeInfo wm, int maxAlertsPerWindow) {
    return new AlertEngine(config(wm, maxAlertsPerWindow, 60_000L), NO_OP); // never fires naturally
  }

  private static AlertEngine engine(
      WireMockRuntimeInfo wm, int maxAlertsPerWindow, PipelineCounters counters) {
    return new AlertEngine(config(wm, maxAlertsPerWindow, 60_000L), NO_OP, counters);
  }

  private static AlertEvent event(int i) {
    return AlertEvent.of("com.example.app.Service", "boom " + i, 0L);
  }

  @Test
  void successfulPublishes_incrementPublishedOnly(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = engine(wm, 10);
    engine.start();
    assertThat(engine.isStarted()).isTrue();

    for (int i = 0; i < 5; i++) {
      engine.submit(event(i));
    }
    engine.stop();

    assertThat(engine.counters().published()).isEqualTo(5);
    assertThat(engine.counters().suppressed()).isZero();
    assertThat(engine.counters().failed()).isZero();
  }

  @Test
  void rateLimitedEvents_incrementSuppressedOnly_thenDigestFlushPublishes(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = engine(wm, 1);
    engine.start();

    // 1 published, 2 over-allowance -> suppressed.
    engine.submit(event(0));
    engine.submit(event(1));
    engine.submit(event(2));

    assertThat(engine.counters().published()).isEqualTo(1);
    assertThat(engine.counters().suppressed()).isEqualTo(2);
    assertThat(engine.counters().failed()).isZero();

    // stop() flushes the pending digest synchronously -> one more successful publish.
    engine.stop();
    assertThat(engine.counters().published()).isEqualTo(2);
    assertThat(engine.counters().suppressed()).isEqualTo(2);
    assertThat(engine.counters().failed()).isZero();
  }

  @Test
  void failedIndividualPublish_incrementsFailedNotSuppressed(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));
    AlertEngine engine = engine(wm, 10);
    engine.start();

    for (int i = 0; i < 3; i++) {
      engine.submit(event(i));
    }

    // Each publish failed (500). Failed counts them; the recordSuppressed digest fold does NOT
    // leak into the suppressed observability counter.
    assertThat(engine.counters().failed()).isEqualTo(3);
    assertThat(engine.counters().suppressed()).isZero();
    assertThat(engine.counters().published()).isZero();

    engine.stop();
  }

  @Test
  void failedDigestFlush_incrementsFailed(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));
    AlertEngine engine = engine(wm, 1);
    engine.start();

    // First publish fails (failed=1, folded into the digest tally); second is rate-limited
    // (suppressed=1, also folded into the digest tally).
    engine.submit(event(0));
    engine.submit(event(1));

    assertThat(engine.counters().failed()).isEqualTo(1);
    assertThat(engine.counters().suppressed()).isEqualTo(1);
    assertThat(engine.counters().published()).isZero();

    // stop() flushes the pending digest, which also fails (500) -> failed increments once more.
    engine.stop();
    assertThat(engine.counters().failed()).isEqualTo(2);
    assertThat(engine.counters().suppressed()).isEqualTo(1);
    assertThat(engine.counters().published()).isZero();
  }

  /**
   * Multiple failed digest windows across a real timer: after a failed digest flush restores its
   * drained tally, the <em>next</em> window's digest fires with that same restored count — and
   * {@code failed} must increment by exactly one per publish attempt, never re-counting the restored
   * digest message count. If the restored count leaked into {@code failed}, the tally sitting across
   * repeated failed flushes would drive {@code failed} far past the number of publish attempts.
   *
   * <p>Deterministic despite the free-running digest timer (and despite the burst-allowance rollover
   * that a sub-latency window can trigger between the two submits): a four-state WireMock scenario
   * answers the first three POSTs with 500 and the fourth with 200. Every 500 keeps the tally
   * non-empty (an individual failure folds it in; a digest failure restores it), so POSTs keep
   * firing until the fourth — whichever thread sends it — hits 200 and drains the tally. Exactly
   * four POSTs therefore occur (three failures, one success) regardless of how the submits split
   * between individual publishes and suppression, so {@code failed == 3} and {@code published == 1}
   * are stable resting values. Only {@code suppressed} (0 or 1) depends on that rollover, so it is
   * left unasserted.
   */
  @Test
  void repeatedFailedDigestFlushes_incrementFailedOncePerAttempt(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(
        post(urlEqualTo("/alerts"))
            .inScenario("digest")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("second"));
    stubFor(
        post(urlEqualTo("/alerts"))
            .inScenario("digest")
            .whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("third"));
    stubFor(
        post(urlEqualTo("/alerts"))
            .inScenario("digest")
            .whenScenarioStateIs("third")
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("fourth"));
    stubFor(
        post(urlEqualTo("/alerts"))
            .inScenario("digest")
            .whenScenarioStateIs("fourth")
            .willReturn(aResponse().withStatus(200))
            .willSetStateTo("done"));

    // Short window so the digest timer fires repeatedly on its own.
    AlertEngine engine = new AlertEngine(config(wm, 1, 150L), NO_OP);
    engine.start();

    // Two events into a max-1 window: at least one individual publish fails and folds its count into
    // the digest tally; the surplus is either suppressed or (on a burst rollover) a second failed
    // individual publish. Either way the tally is non-empty going into the digest windows.
    engine.submit(event(0));
    engine.submit(event(1));

    // The successful fourth flush drains the tally; with it empty, subsequent ticks short-circuit,
    // so once published==1 the counters never move again.
    for (int i = 0; i < 100 && engine.counters().published() == 0; i++) {
      Thread.sleep(50);
    }

    assertThat(engine.counters().published()).as("fourth flush succeeded, draining the tally")
        .isEqualTo(1);
    assertThat(engine.counters().failed())
        .as("one increment per failed publish attempt (3), never the restored message count")
        .isEqualTo(3);

    engine.stop();

    // Resting state: the drained tally means stop() has nothing to flush and nothing moved.
    assertThat(engine.counters().published()).isEqualTo(1);
    assertThat(engine.counters().failed()).isEqualTo(3);
  }

  /**
   * The 3-arg constructor lets an externally-owned {@link PipelineCounters} holder be shared across
   * successive engine instances — exactly what the Logback appender does when it rebuilds its engine
   * across a {@code stop()}/{@code start()} cycle or a context reset. The tallies must accumulate
   * across instances, never reset with the new engine.
   */
  @Test
  void sharedCountersHolder_accumulatesAcrossEngineInstances(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    PipelineCounters shared = new PipelineCounters();

    AlertEngine first = engine(wm, 10, shared);
    assertThat(first.counters()).isSameAs(shared);
    first.start();
    first.submit(event(0));
    first.submit(event(1));
    first.stop();
    assertThat(shared.published()).isEqualTo(2);

    // A brand-new engine sharing the same holder continues the tally.
    AlertEngine second = engine(wm, 10, shared);
    assertThat(second.counters()).isSameAs(shared);
    second.start();
    second.submit(event(2));
    second.stop();

    assertThat(shared.published()).as("accumulated across both engines, not reset").isEqualTo(3);
    assertThat(shared.suppressed()).isZero();
    assertThat(shared.failed()).isZero();
  }
}
