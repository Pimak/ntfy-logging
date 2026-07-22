package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
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

  private static AlertEngine engine(WireMockRuntimeInfo wm, int maxAlertsPerWindow) {
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .maxAlertsPerWindow(maxAlertsPerWindow)
            .suppressionWindow(java.time.Duration.ofMillis(60_000)) // never fires naturally
            .build();
    return new AlertEngine(config, NO_OP);
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
}
