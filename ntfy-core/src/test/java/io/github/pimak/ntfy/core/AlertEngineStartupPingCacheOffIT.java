package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * What {@code cache=false} does — and deliberately does not do — to the startup self-test.
 *
 * <p>It does not change its mode, and pinning that is the point of this class. It is tempting to
 * argue that under {@code Cache: no} a publishing self-test proves less, since the server stores
 * nothing. But the self-test has only ever read the server's response: it never proved a subscriber
 * received anything, cached or not. What a publish DOES prove — endpoint, credentials, topic, and
 * above all WRITE permission, which a read-only probe cannot — is untouched by caching.
 *
 * <p>Downgrading it to a probe would trade a real guarantee for nothing, and would defeat {@code
 * startup-ping-fail-fast} for exactly the misconfiguration it exists to catch: a read-only token
 * passes a probe and then fails every real alert.
 */
@WireMockTest
class AlertEngineStartupPingCacheOffIT {

  private static final class RecordingDiagnostics implements Diagnostics {
    // Copy-on-write, not ArrayList: the self-test runs on its own thread and writes here while the
    // test thread reads, so a plain list races — intermittently a ConcurrentModificationException,
    // and always a visibility question. The other startup-ping harness in this package
    // (AlertEngineStartupPingIT.CapturingDiagnostics) is copy-on-write for exactly this reason.
    final List<String> infos = new CopyOnWriteArrayList<>();
    final List<String> warns = new CopyOnWriteArrayList<>();

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
      warns.add(msg);
    }
  }

  private static RecordingDiagnostics runSelfTest(
      WireMockRuntimeInfo wm, StartupPingMode mode, boolean cache) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));

    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .startupPing(mode)
            .cache(cache)
            .build();

    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine = new AlertEngine(config, diagnostics);
    engine.start();
    awaitSelfTestReported(diagnostics);
    engine.stop();
    return diagnostics;
  }

  /**
   * Waits for the self-test's RESULT line specifically. The worker runs on its own thread, so
   * stopping the engine straight away would interrupt the very request under test.
   */
  private static void awaitSelfTestReported(RecordingDiagnostics diagnostics) {
    long deadline = System.currentTimeMillis() + 3000;
    while (!reported(diagnostics) && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(reported(diagnostics)).as("self-test diagnostic was never emitted").isTrue();
  }

  private static boolean reported(RecordingDiagnostics diagnostics) {
    return java.util.stream.Stream.concat(diagnostics.infos.stream(), diagnostics.warns.stream())
        .anyMatch(line -> line.contains("PASSED") || line.contains("FAILED"));
  }

  @Test
  void cacheOff_leavesAPublishingSelfTestPublishing(WireMockRuntimeInfo wm) {
    runSelfTest(wm, StartupPingMode.PUBLISH, false);

    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts"))))
        .as("a publish is what proves write permission, and caching has no bearing on that")
        .hasSize(1);
    assertThat(findAll(getRequestedFor(urlPathEqualTo("/alerts/json")))).isEmpty();
  }

  @Test
  void cacheOff_selfTestCarriesTheCacheHeaderLikeEveryOtherPublish(WireMockRuntimeInfo wm) {
    runSelfTest(wm, StartupPingMode.PUBLISH, false);

    List<LoggedRequest> published = findAll(postRequestedFor(urlEqualTo("/alerts")));
    // Asserted before indexing: a missing publish should read as "nothing was published", not as an
    // IndexOutOfBoundsException the next reader has to decode. This also keeps the test standing on
    // its own rather than leaning on the one above it having already checked.
    assertThat(published).hasSize(1);

    // The self-test publishes through the same target as an alert, so it honours the same delivery
    // policy. A self-test exempt from it would be exercising a path nothing else takes.
    assertThat(published.get(0).getHeader("Cache")).isEqualTo("no");
  }

  @Test
  void cacheOff_addsNoWarningOfItsOwn(WireMockRuntimeInfo wm) {
    RecordingDiagnostics diagnostics = runSelfTest(wm, StartupPingMode.PUBLISH, false);

    assertThat(diagnostics.warns).isEmpty();
  }

  @Test
  void probeStaysAProbe(WireMockRuntimeInfo wm) {
    runSelfTest(wm, StartupPingMode.PROBE, false);

    assertThat(findAll(getRequestedFor(urlPathEqualTo("/alerts/json")))).hasSize(1);
    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts")))).isEmpty();
  }
}
