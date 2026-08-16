package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * Drives a real {@link AlertEngine} against a loopback WireMock server and asserts what the optional
 * WARN route actually puts on the wire: which request path each level lands on, which {@code
 * Priority}/{@code Tags} headers it carries, that the two routes draw from INDEPENDENT storm
 * budgets, and — the regression guard that matters most to every existing user — that with no {@code
 * warn-topic} configured the engine behaves exactly as it did before this feature existed.
 */
@WireMockTest
class AlertEngineLevelRoutingIT {

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static NtfyConfig.Builder baseConfig(WireMockRuntimeInfo wm) {
    return NtfyConfig.builder()
        .url("http://localhost:" + wm.getHttpPort())
        .topic("alerts")
        .maxAlertsPerWindow(2)
        // Long enough that the digest timer never fires on its own; the tests drive it via stop().
        .suppressionWindow(Duration.ofMillis(60_000L))
        // Inline delivery so an individual alert is observable on the wire rather than sitting in
        // the async queue when stop() runs — same reason as AlertEngineMdcBodyIT.
        .asyncEnabled(false);
  }

  private static AlertEvent event(String logger, AlertLevel level) {
    return AlertEvent.of(logger, "boom", 0L).withLevel(level);
  }

  private static void stubAccept() {
    stubFor(post(urlMatching("/.*")).willReturn(aResponse().withStatus(200)));
  }

  @Test
  void withoutWarnTopic_warnEventsAreNeverPublished_andErrorsAreUnaffected(
      WireMockRuntimeInfo wm) {
    stubAccept();
    AlertEngine engine = new AlertEngine(baseConfig(wm).build(), NO_OP);
    engine.start();
    try {
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
    } finally {
      engine.stop();
    }

    // Exactly one request, on the error topic: the WARN event was dropped by the engine's own half
    // of the double floor, with no warn-topic ever named.
    List<LoggedRequest> all = findAll(postRequestedFor(urlMatching("/.*")));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getUrl()).isEqualTo("/alerts");
  }

  @Test
  void withWarnTopic_eachLevelLandsOnItsOwnTopicWithItsOwnPriorityAndTags(
      WireMockRuntimeInfo wm) {
    stubAccept();
    AlertEngine engine =
        new AlertEngine(baseConfig(wm).warnTopic("alerts-warn").build(), NO_OP);
    engine.start();
    try {
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
    } finally {
      engine.stop();
    }

    LoggedRequest error = findAll(postRequestedFor(urlEqualTo("/alerts"))).get(0);
    assertThat(error.getHeader("Priority")).isEqualTo("high");
    assertThat(error.getHeader("Tags")).isEqualTo("rotating_light");

    LoggedRequest warn = findAll(postRequestedFor(urlEqualTo("/alerts-warn"))).get(0);
    // The defaults are deliberately quieter than the error route's high/rotating_light.
    assertThat(warn.getHeader("Priority")).isEqualTo("default");
    assertThat(warn.getHeader("Tags")).isEqualTo("warning");
  }

  @Test
  void warnPriorityAndTagsAreConfigurable(WireMockRuntimeInfo wm) {
    stubAccept();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .warnPriority("min")
                .warnTags("eyes")
                .build(),
            NO_OP);
    engine.start();
    try {
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
    } finally {
      engine.stop();
    }

    LoggedRequest warn = findAll(postRequestedFor(urlEqualTo("/alerts-warn"))).get(0);
    assertThat(warn.getHeader("Priority")).isEqualTo("min");
    assertThat(warn.getHeader("Tags")).isEqualTo("eyes");
  }

  @Test
  void aWarningStormNeverConsumesTheErrorAllowance(WireMockRuntimeInfo wm) {
    stubAccept();
    // maxAlertsPerWindow is 2 PER ROUTE. Exhausting the warn budget several times over must leave
    // the error budget untouched — this is the whole reason the routes hold separate limiters
    // rather than sharing one, and the property the ERROR-only floor used to guarantee.
    AlertEngine engine =
        new AlertEngine(baseConfig(wm).warnTopic("alerts-warn").build(), NO_OP);
    engine.start();
    try {
      for (int i = 0; i < 20; i++) {
        engine.submit(event("com.example.Noisy", AlertLevel.WARN));
      }
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
    } finally {
      engine.stop();
    }

    // Both errors got through as individual alerts despite 20 warnings arriving first.
    List<LoggedRequest> errors = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(errors).hasSize(2);
    // Real individual alerts, not a digest: the error budget was never drawn down by the warnings.
    assertThat(errors)
        .allSatisfy(r -> assertThat(r.getBodyAsString()).doesNotContain("suppressed"));

    // The warn route published its 2 individual alerts and folded the other 18 into its own digest,
    // emitted on stop() to the warn topic.
    List<LoggedRequest> warns = findAll(postRequestedFor(urlEqualTo("/alerts-warn")));
    assertThat(warns).hasSize(3);
    LoggedRequest digest = warns.get(2);
    assertThat(digest.getBodyAsString()).contains("18 warnings suppressed");
    // The warn digest stays on the warn route's own priority rather than escalating to `urgent`:
    // routing warnings to a quiet channel must not be undone by the digest that reports a burst.
    assertThat(digest.getHeader("Priority")).isEqualTo("default");
  }

  @Test
  void eachRouteDigestsSeparately_onItsOwnTopicAndWithItsOwnNoun(WireMockRuntimeInfo wm) {
    stubAccept();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).maxAlertsPerWindow(1).warnTopic("alerts-warn").build(), NO_OP);
    engine.start();
    try {
      // One over the allowance on each route, so both digests have something to report.
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
    } finally {
      engine.stop();
    }

    List<LoggedRequest> errors = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(errors).hasSize(2);
    assertThat(errors.get(1).getBodyAsString()).contains("1 errors suppressed");
    assertThat(errors.get(1).getHeader("Priority")).isEqualTo("urgent");

    List<LoggedRequest> warns = findAll(postRequestedFor(urlEqualTo("/alerts-warn")));
    assertThat(warns).hasSize(2);
    assertThat(warns.get(1).getBodyAsString()).contains("1 warnings suppressed");
  }

  @Test
  void invalidWarnTopic_dropsOnlyTheWarnRoute_andLeavesErrorAlertingActive(
      WireMockRuntimeInfo wm) {
    stubAccept();
    // A topic ntfy itself would reject. Unlike the main `topic`, this must NOT cost activation:
    // a mistyped optional extra may never silence error alerting.
    AlertEngine engine =
        new AlertEngine(baseConfig(wm).warnTopic("not/a/valid/topic").build(), NO_OP);
    engine.start();
    try {
      assertThat(engine.isStarted()).isTrue();
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
    } finally {
      engine.stop();
    }

    List<LoggedRequest> all = findAll(postRequestedFor(urlMatching("/.*")));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getUrl()).isEqualTo("/alerts");
  }

  @Test
  void warnTopicFromAClasspathFileAlone_isRefusedWithoutTheOptIn(WireMockRuntimeInfo wm) {
    stubAccept();
    // Simulates what ConfigLoader records when warn-topic was resolved ONLY from a classpath
    // ntfy.properties: any jar can ship one, and warn-topic both names a destination and widens how
    // much log content leaves the host.
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm).warnTopic("alerts-warn").warnTopicFromClasspathFile(true).build(),
            NO_OP);
    engine.start();
    try {
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
      engine.submit(event("com.example.Svc", AlertLevel.ERROR));
    } finally {
      engine.stop();
    }

    List<LoggedRequest> all = findAll(postRequestedFor(urlMatching("/.*")));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getUrl()).isEqualTo("/alerts");
  }

  @Test
  void warnTopicFromAClasspathFile_isHonoredOnceTheOperatorOptsIn(WireMockRuntimeInfo wm) {
    stubAccept();
    AlertEngine engine =
        new AlertEngine(
            baseConfig(wm)
                .warnTopic("alerts-warn")
                .warnTopicFromClasspathFile(true)
                .allowClasspathEndpoint(true)
                .build(),
            NO_OP);
    engine.start();
    try {
      engine.submit(event("com.example.Svc", AlertLevel.WARN));
    } finally {
      engine.stop();
    }

    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts-warn")))).hasSize(1);
  }
}
