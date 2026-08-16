package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The fail-fast contract. Two things must hold simultaneously and are easy to get wrong together: a
 * failed self-test must NOT be able to kill the host application by default, and when fail-fast IS
 * requested the abort must not leak the threads {@code start()} had already acquired.
 */
@WireMockTest
class AlertEngineStartupPingFailFastTest {

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

  private static NtfyConfig config(WireMockRuntimeInfo wm, boolean failFast) {
    return NtfyConfig.builder()
        .url("http://localhost:" + wm.getHttpPort())
        .topic("alerts")
        .startupPing(StartupPingMode.PROBE)
        .startupPingFailFast(failFast)
        .build();
  }

  /**
   * One test here deliberately tears the engine down mid-request, so an HTTP exchange can still be
   * unwinding when the test method returns. Left alone that thread outlives this class and trips the
   * JVM-wide thread-leak assertion in whichever class runs next — see {@link NtfyThreads}.
   */
  @AfterEach
  void drainBeforeHandingTheJvmToTheNextTestClass() throws InterruptedException {
    NtfyThreads.awaitQuiesced();
  }

  @Test
  void byDefaultAFailedSelfTestNeverKillsTheApplication(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(401)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine = new AlertEngine(config(wm, false), diag);

    assertThatCode(engine::start).doesNotThrowAnyException();

    // A logging backend must never be able to take down the service it instruments: the engine
    // stays started and fully usable for real alerts even though its self-test failed.
    assertThat(engine.isStarted()).isTrue();
    long deadline = System.currentTimeMillis() + 3000;
    while (diag.warns.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertThat(diag.warns).contains(messages.statusStartupPingFailed(401, null));
    assertThat(diag.warns).doesNotContain(messages.statusStartupPingFailFastRefused());
    engine.stop();
  }

  @Test
  void failFastAbortsStartupWithTheCredentialSafeRefusal(WireMockRuntimeInfo wm) {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(401)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine = new AlertEngine(config(wm, true), diag);

    assertThatThrownBy(engine::start)
        .isInstanceOf(NtfyStartupSelfTestException.class)
        .hasMessage(messages.statusStartupPingFailFastRefused());

    assertThat(engine.isStarted()).isFalse();
    // The cause is still diagnosed, not just the refusal — otherwise the operator learns only that
    // it refused, never why.
    assertThat(diag.warns).contains(messages.statusStartupPingFailed(401, null));
  }

  @Test
  void failFastAbortReleasesEveryResourceBeforeThrowing(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(500)));
    AlertEngine engine = new AlertEngine(config(wm, true), new CapturingDiagnostics());

    assertThatThrownBy(engine::start).isInstanceOf(NtfyStartupSelfTestException.class);

    // Throwing out of start() without tearing down would strand the HTTP pool and leave the digest
    // timer ticking forever in a process that believes it has no engine at all.
    NtfyThreads.awaitNoEngineThreads();
    assertThat(NtfyThreads.anyEngineThreadAlive()).isFalse();
    // stop() on the already-torn-down engine must stay safe.
    assertThatCode(engine::stop).doesNotThrowAnyException();
  }

  @Test
  void failFastRunsInlineSoTheResultIsKnownBeforeStartReturns(WireMockRuntimeInfo wm) {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine = new AlertEngine(config(wm, true), diag);

    engine.start();

    // No waiting: an inline self-test has already reported by the time start() returns. That is
    // what makes aborting possible at all, and is the cost fail-fast deliberately opts into.
    assertThat(diag.infos).contains(messages.statusStartupPingProbePassed());
    assertThat(engine.isStarted()).isTrue();
    engine.stop();
  }

  @Test
  void aPassingFailFastSelfTestStartsNormally(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = new AlertEngine(config(wm, true), new CapturingDiagnostics());

    assertThatCode(engine::start).doesNotThrowAnyException();
    assertThat(engine.isStarted()).isTrue();

    engine.stop();
    NtfyThreads.awaitNoEngineThreads();
    assertThat(NtfyThreads.anyEngineThreadAlive()).isFalse();
  }

  @Test
  void asyncSelfTestLeavesNoThreadBehindAfterStop(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = new AlertEngine(config(wm, false), new CapturingDiagnostics());

    engine.start();
    engine.stop();

    NtfyThreads.awaitNoEngineThreads();
    assertThat(NtfyThreads.anyEngineThreadAlive()).isFalse();
  }

  @Test
  void stopDuringAnInFlightSelfTestStaysSilentRatherThanBlamingTheConfiguration(
      WireMockRuntimeInfo wm) throws Exception {
    // Hold the probe open past stop() so the round-trip is guaranteed to be in flight when the
    // engine is torn down underneath it.
    stubFor(
        get(urlPathEqualTo("/alerts/json"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine = new AlertEngine(config(wm, false), diag);

    engine.start();
    engine.stop();
    Thread.sleep(300);

    // The failure such a teardown manufactures is an artifact of the shutdown, not a configuration
    // problem — reporting it would send an operator chasing a nonexistent bug.
    assertThat(diag.warns).noneMatch(s -> s.contains("startup self-test"));
    assertThat(diag.infos).noneMatch(s -> s.contains("startup self-test"));
  }
}
