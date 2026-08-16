package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * End-to-end wire behaviour of the opt-in startup self-test against a WireMock ntfy endpoint: what
 * each mode actually puts on the network, that {@code off} puts nothing there at all, and that the
 * self-test fires exactly once per activation.
 *
 * <p>Diagnostic wording and failure classification are covered by {@link
 * AlertEngineStartupPingDiagnosticsTest}; the fail-fast contract by {@link
 * AlertEngineStartupPingFailFastTest}.
 */
@WireMockTest
class AlertEngineStartupPingIT {

  private final AlertMessages messages = AlertMessages.forLocale(Locale.ENGLISH);

  private static final class CapturingDiagnostics implements Diagnostics {
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

  private static NtfyConfig.Builder baseConfig(WireMockRuntimeInfo wm) {
    return NtfyConfig.builder()
        .url("http://localhost:" + wm.getHttpPort())
        .topic("alerts")
        .appName("test-app");
  }

  /**
   * The RFC 2047 encoded-word form the publisher uses for any non-ASCII header value, mirroring
   * {@code NtfyPublisher.asciiSafeTitle}.
   */
  private static String rfc2047(String value) {
    return "=?UTF-8?B?"
        + java.util.Base64.getEncoder()
            .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "?=";
  }

  /**
   * Waits for the async self-test worker to have produced its own diagnostic line.
   *
   * <p>Deliberately matches on the self-test line itself rather than counting total diagnostics:
   * {@code start()} emits a variable number of lines depending on config (a token over cleartext
   * http adds a warning, for instance), so a count-based wait can be satisfied by start()'s own
   * output and let {@code stop()} interrupt the worker before it ever reaches the network.
   */
  private static void awaitSelfTestReported(CapturingDiagnostics diag) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3000;
    while (!reported(diag) && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertThat(reported(diag)).as("self-test diagnostic was never emitted").isTrue();
  }

  private static boolean reported(CapturingDiagnostics diag) {
    return java.util.stream.Stream.concat(diag.infos.stream(), diag.warns.stream())
        .anyMatch(s -> s.contains("startup self-test"));
  }


  /** See {@link NtfyThreads}: keeps this class from handing a still-unwinding HTTP exchange to the
   * next test class, where a JVM-wide thread-leak assertion would blame it. */
  @org.junit.jupiter.api.AfterEach
  void drainBeforeHandingTheJvmToTheNextTestClass() throws InterruptedException {
    NtfyThreads.awaitQuiesced();
  }

  @Test
  void off_isATrueNoOp_noRequestAndNoExtraDiagnostic(WireMockRuntimeInfo wm) throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine = new AlertEngine(baseConfig(wm).build(), diag);

    engine.start();
    // Give any (incorrectly dispatched) background worker a chance to hit the server before we
    // assert that nothing did.
    Thread.sleep(200);
    engine.stop();

    verify(0, anyRequestedFor(anyUrl()));
    // The default diagnostic stream must stay byte-identical to a build without this feature:
    // exactly the ACTIVE line and the exclusions line, and no self-test line of any kind.
    assertThat(diag.infos).hasSize(2);
    assertThat(diag.infos).noneMatch(s -> s.contains("self-test"));
    assertThat(diag.warns).isEmpty();
  }

  @Test
  void probe_issuesOneAuthenticatedPollingGet_andPublishesNothing(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .token("tk_secret")
                // The endpoint is cleartext http, so the strict default would refuse activation
                // outright and the self-test would never run — opt out to exercise the probe.
                .requireHttpsForCredentials(false)
                .startupPing(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();

    // poll=1 so the server answers instead of holding a stream open; since=none so a busy topic
    // does not replay its cache just to prove the endpoint answers.
    verify(
        1,
        getRequestedFor(urlEqualTo("/alerts/json?poll=1&since=none"))
            .withHeader("Authorization", equalTo("Bearer tk_secret")));
    verify(0, postRequestedFor(anyUrl()));
    assertThat(diag.infos).contains(messages.statusStartupPingProbePassed());
    // The only warning is the cleartext-credential one this test deliberately opted into; the
    // self-test itself must contribute nothing to the warn stream on success.
    assertThat(diag.warns).containsExactly(messages.statusCredentialsOverPlainHttp());
  }

  @Test
  void publish_postsOneLowPriorityTestNotification(WireMockRuntimeInfo wm) throws Exception {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(baseConfig(wm).startupPing(StartupPingMode.PUBLISH).build(), diag);

    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();

    // "low", never the configured error-priority: a passing self-test is good news and must not
    // page anyone, but stays visible in the client so it can be confirmed on the device.
    //
    // The Title header is asserted as its RFC 2047 encoded-word form, which is what the publisher
    // legitimately puts on the wire for any non-ASCII title (the em dash in the composed label) —
    // the same treatment digest titles already get. Title COMPOSITION is asserted separately, at
    // the AlertMessages level, where it is readable.
    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("low"))
            .withHeader("Tags", equalTo("white_check_mark"))
            .withHeader("Title", equalTo(rfc2047(messages.selfTestTitle("test-app"))))
            .withRequestBody(equalTo(messages.selfTestBody())));
    assertThat(diag.infos).contains(messages.statusStartupPingPublishPassed());
    assertThat(diag.warns).isEmpty();
  }

  @Test
  void selfTestFiresExactlyOnce_evenAcrossARedundantSecondStart(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(baseConfig(wm).startupPing(StartupPingMode.PROBE).build(), diag);

    engine.start();
    awaitSelfTestReported(diag);
    engine.start(); // no-op: already started
    Thread.sleep(200);
    engine.stop();

    verify(1, getRequestedFor(urlPathEqualTo("/alerts/json")));
  }

  @Test
  void selfTestDoesNotRun_whenTheEngineRefusesActivation(WireMockRuntimeInfo wm) throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    // A token over cleartext http with the default strict transport policy: start() refuses
    // activation. The self-test must never probe a configuration the engine itself rejected.
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).token("tk_secret").startupPing(StartupPingMode.PROBE).build(), diag);

    engine.start();
    Thread.sleep(200);

    assertThat(engine.isStarted()).isFalse();
    verify(0, anyRequestedFor(anyUrl()));
    assertThat(diag.warns).containsExactly(messages.statusCredentialsOverPlainHttpRefused());
  }

  @Test
  void selfTestDoesNotRun_whenDisabled(WireMockRuntimeInfo wm) throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).enabled(false).startupPing(StartupPingMode.PUBLISH).build(), diag);

    engine.start();
    Thread.sleep(200);

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(engine.isStarted()).isFalse();
  }

  @Test
  void selfTestNeverPollutesTheAlertPipelineCounters(WireMockRuntimeInfo wm) throws Exception {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    PipelineCounters counters = new PipelineCounters();
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).startupPing(StartupPingMode.PUBLISH).build(), diag, counters);

    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();

    // The counters describe application alert traffic; a boot-time self-check is not that, and a
    // self-test publish counted as `published` would silently inflate observability dashboards.
    assertThat(counters.published()).isZero();
    assertThat(counters.failed()).isZero();
    assertThat(counters.suppressed()).isZero();
  }
}
