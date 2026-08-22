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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Under {@code Cache: no} a 2xx proves the server accepted the message, not that any subscriber
 * will ever see it — the message reaches connected subscribers only and is never replayed. A
 * publishing self-test would therefore report "verified" on a boot where the operator's phone
 * happened to be offline, which is precisely the silent-success this library exists to avoid.
 *
 * <p>So the engine downgrades a publishing self-test to a probe, which stays conclusive because it
 * asks the server a question rather than depending on a stored message. It does NOT refuse the
 * configuration: both settings are legitimate on their own, and rejecting a combination the
 * operator explicitly asked for would turn a guard into an obstacle.
 */
@WireMockTest
class AlertEngineStartupPingCacheOffIT {

  private static final class RecordingDiagnostics implements Diagnostics {
    final List<String> infos = new ArrayList<>();
    final List<String> warns = new ArrayList<>();

    @Override
    public void info(String msg) {
      infos.add(msg);
    }

    @Override
    public void warn(String msg) {
      warns.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {}
  }

  private static RecordingDiagnostics startWith(WireMockRuntimeInfo wm, boolean cache) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));

    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .startupPing(StartupPingMode.PUBLISH)
            .cache(cache)
            .build();

    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine = new AlertEngine(config, diagnostics);
    engine.start();
    // The self-test runs on its own worker; stopping straight away would interrupt it before it
    // ever reaches the network. Wait for its own diagnostic line rather than counting lines —
    // start() emits a variable number depending on config.
    awaitSelfTestReported(diagnostics);
    engine.stop();
    return diagnostics;
  }

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

  /**
   * Matches the self-test's RESULT line, not merely any line mentioning the self-test: the
   * downgrade warning names it too, and waiting on that would return before the probe has left,
   * letting stop() interrupt the very request under test.
   */
  private static boolean reported(RecordingDiagnostics diagnostics) {
    return java.util.stream.Stream.concat(diagnostics.infos.stream(), diagnostics.warns.stream())
        .anyMatch(line -> line.contains("PASSED") || line.contains("FAILED"));
  }

  @Test
  void cacheOff_downgradesAPublishingSelfTestToAProbe(WireMockRuntimeInfo wm) {
    startWith(wm, false);

    assertThat(findAll(getRequestedFor(urlPathEqualTo("/alerts/json"))))
        .as("the self-test must ask the server a question rather than publish a message it cannot"
            + " prove was delivered")
        .hasSize(1);
    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts")))).isEmpty();
  }

  @Test
  void cacheOff_saysWhyItDowngraded(WireMockRuntimeInfo wm) {
    RecordingDiagnostics diagnostics = startWith(wm, false);

    // A silent downgrade would leave an operator reading "PASSED (probe)" while having configured
    // publish, with nothing to explain the discrepancy.
    assertThat(diagnostics.warns).anySatisfy(w -> assertThat(w).contains("cache"));
  }

  @Test
  void aWarnLegConfiguredToPublishButNeverRunDoesNotProduceTheWarning(WireMockRuntimeInfo wm) {
    // startup-ping-warn=publish with no warn-topic never becomes a leg at all, and the error
    // leg was configured as a probe to begin with. Nothing was downgraded, so nothing should
    // say it was — a warning here would send an operator looking for a route that does not
    // exist.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));

    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .startupPing(StartupPingMode.PROBE)
            .startupPingWarn(StartupPingMode.PUBLISH)
            .cache(false)
            .build();

    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine = new AlertEngine(config, diagnostics);
    engine.start();
    awaitSelfTestReported(diagnostics);
    engine.stop();

    assertThat(diagnostics.warns).noneSatisfy(w -> assertThat(w).contains("probe instead"));
  }

  @Test
  void cacheOn_leavesAPublishingSelfTestAlone(WireMockRuntimeInfo wm) {
    RecordingDiagnostics diagnostics = startWith(wm, true);

    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts")))).hasSize(1);
    assertThat(findAll(getRequestedFor(urlPathEqualTo("/alerts/json")))).isEmpty();
    assertThat(diagnostics.warns).noneSatisfy(w -> assertThat(w).contains("cache"));
  }
}
