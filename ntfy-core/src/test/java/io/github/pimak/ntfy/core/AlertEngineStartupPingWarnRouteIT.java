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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The WARN route's own self-test, and the failure notification that rides on a healthy route.
 *
 * <p>The two routes are configured independently ({@code startup-ping} vs {@code
 * startup-ping-warn}), so the combinations that matter are: each alone, both together, and — the
 * one with a real safety property attached — the failure notification, which must never publish to
 * a topic no self-test has just verified.
 */
@WireMockTest
class AlertEngineStartupPingWarnRouteIT {

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

  @AfterEach
  void drainBeforeHandingTheJvmToTheNextTestClass() throws InterruptedException {
    NtfyThreads.awaitQuiesced();
  }

  private static NtfyConfig.Builder baseConfig(WireMockRuntimeInfo wm) {
    return NtfyConfig.builder()
        .url("http://localhost:" + wm.getHttpPort())
        .topic("alerts")
        .appName("test-app");
  }

  private static void awaitSettled(CapturingDiagnostics diag, int lines)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3000;
    while (count(diag) < lines && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
  }

  private static long count(CapturingDiagnostics diag) {
    return java.util.stream.Stream.concat(diag.infos.stream(), diag.warns.stream())
        .filter(s -> s.contains("startup self-test"))
        .count();
  }

  @Test
  void warnRouteIsProbedOnItsOwnTopicAndReportedAsItsOwnRoute(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts-warn/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .startupPingWarn(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    awaitSettled(diag, 1);
    engine.stop();

    verify(1, getRequestedFor(urlPathEqualTo("/alerts-warn/json")));
    // startup-ping stayed off, so the primary topic is never touched: the two flags are independent.
    verify(0, getRequestedFor(urlPathEqualTo("/alerts/json")));
    assertThat(diag.infos)
        .anySatisfy(l -> assertThat(l).contains("WARN route").contains("alerts-warn"));
  }

  @Test
  void bothRoutesTestedIndependently_eachOnItsOwnTopic(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts-warn/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .startupPing(StartupPingMode.PROBE)
                .startupPingWarn(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    awaitSettled(diag, 2);
    engine.stop();

    verify(1, getRequestedFor(urlPathEqualTo("/alerts/json")));
    verify(1, getRequestedFor(urlPathEqualTo("/alerts-warn/json")));
    assertThat(count(diag)).isEqualTo(2);
  }

  @Test
  void warnSelfTestIsSkippedWhenTheEngineWithdrewTheWarnRoute(WireMockRuntimeInfo wm)
      throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    // "bad/topic" is not a valid ntfy topic name, so the engine withdraws the route while keeping
    // ERROR alerting. Probing a retracted route would report on something that cannot carry alerts.
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).warnTopic("bad/topic").startupPingWarn(StartupPingMode.PROBE).build(),
            diag);

    engine.start();
    Thread.sleep(250);
    engine.stop();

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(diag.warns).contains(messages.statusInvalidWarnTopic());
    assertThat(count(diag)).isZero();
  }

  @Test
  void aBrokenWarnRouteIsAnnouncedOnTheHealthyErrorRoute(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts-warn/json")).willReturn(aResponse().withStatus(403)));
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .startupPing(StartupPingMode.PROBE)
                .startupPingWarn(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    awaitSettled(diag, 2);
    Thread.sleep(250);
    engine.stop();

    // The whole point: a status line nobody greps becomes a notification on the channel that works.
    verify(
        1,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Priority", equalTo("default"))
            .withHeader("Tags", equalTo("warning")));
    verify(0, postRequestedFor(urlEqualTo("/alerts-warn")));
  }

  @Test
  void theFailureNotificationNeverPingsATopicNoSelfTestVerified(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(401)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    // Only the ERROR route is tested, and it FAILS. There is no verified route to announce it on,
    // so nothing may be published — least of all to the topic that just rejected us.
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).warnTopic("alerts-warn").startupPing(StartupPingMode.PROBE).build(),
            diag);

    engine.start();
    awaitSettled(diag, 1);
    Thread.sleep(250);
    engine.stop();

    verify(0, postRequestedFor(anyUrl()));
    assertThat(diag.warns).anySatisfy(l -> assertThat(l).contains("401"));
  }

  @Test
  void withTheWarnSelfTestOff_aHealthyErrorRouteIsNeverPinged(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    // A warn-topic is configured but its self-test is off (the default). Nothing about the WARN
    // route is tested, so nothing about it can fail, so the ERROR topic receives no publish at all.
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).warnTopic("alerts-warn").startupPing(StartupPingMode.PROBE).build(),
            diag);

    engine.start();
    awaitSettled(diag, 1);
    Thread.sleep(250);
    engine.stop();

    verify(0, postRequestedFor(anyUrl()));
    verify(0, getRequestedFor(urlPathEqualTo("/alerts-warn/json")));
  }

  @Test
  void notifyFailuresCanBeTurnedOffToKeepProbeAbsolutelyPublishFree(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts-warn/json")).willReturn(aResponse().withStatus(403)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .startupPing(StartupPingMode.PROBE)
                .startupPingWarn(StartupPingMode.PROBE)
                .startupPingNotifyFailures(false)
                .build(),
            diag);

    engine.start();
    awaitSettled(diag, 2);
    Thread.sleep(250);
    engine.stop();

    verify(0, postRequestedFor(anyUrl()));
  }

  @Test
  void failFastCoversTheWarnRouteToo(WireMockRuntimeInfo wm) {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    stubFor(get(urlPathEqualTo("/alerts-warn/json")).willReturn(aResponse().withStatus(403)));
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .startupPing(StartupPingMode.PROBE)
                .startupPingWarn(StartupPingMode.PROBE)
                .startupPingFailFast(true)
                .build(),
            new CapturingDiagnostics());

    // A route whose self-test was explicitly enabled is a route the operator asked to be verified,
    // so its failure gates startup exactly like the primary one's.
    org.assertj.core.api.Assertions.assertThatThrownBy(engine::start)
        .isInstanceOf(NtfyStartupSelfTestException.class);
    assertThat(engine.isStarted()).isFalse();
    // The failure notification still goes out before teardown — aborting must not cost the report.
    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }
}
