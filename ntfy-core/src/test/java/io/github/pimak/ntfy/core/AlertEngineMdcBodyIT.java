package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * Drives a real {@link AlertEngine} against a loopback WireMock server and asserts the ON-THE-WIRE
 * body layout of the allow-listed MDC context block: where its lines sit (high, right under {@code
 * Logger:}, so {@link PayloadTruncator} sacrifices the tail before them), that they follow the
 * CONFIGURED order rather than the MDC map's own, and — the regression guard that matters most to
 * every existing user — that an event with no MDC produces byte-for-byte the body it produced before
 * this feature existed.
 */
@WireMockTest
class AlertEngineMdcBodyIT {

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static AlertEngine engine(WireMockRuntimeInfo wm) {
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .maxAlertsPerWindow(10)
            .suppressionWindow(java.time.Duration.ofMillis(60_000L)) // never fires naturally
            // Explicit 2.0 opt-out, matching AlertEnginePipelineCountersIT: delivery is
            // asynchronous by default, so an individual alert is still sitting in the bounded
            // queue when stop() runs — and stop() folds queued-but-unsent events into the storm
            // digest rather than delivering them. These tests assert on the INDIVIDUAL alert
            // body, so they need inline delivery to observe it at all.
            .asyncEnabled(false)
            .build();
    return new AlertEngine(config, NO_OP);
  }

  /** An event with a cause chain and one frame, so the body has content on BOTH sides of the MDC. */
  private static AlertEvent eventWith(Map<String, String> mdcValues) {
    return new AlertEvent(
        "com.example.app.Service",
        "boom",
        0L,
        List.of(new AlertEvent.Cause("java.lang.IllegalStateException", "bad")),
        List.of("com.example.app.Service.run(Service.java:1)"),
        Set.of(),
        mdcValues);
  }

  private static List<String> publishedBodyLines(AlertEngine engine, AlertEvent event) {
    engine.start();
    engine.submit(event);
    engine.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    return List.of(requests.get(0).getBodyAsString().split("\n", -1));
  }

  @Test
  void mdcLines_areRenderedAfterLoggerAndBeforeCausedBy(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put("tenant", "acme");
    mdc.put("correlationId", "c-42");

    List<String> lines =
        publishedBodyLines(
            engine(wm), eventWith(MdcProjector.project(List.of("tenant", "correlationId"), mdc::get)));

    int loggerLine = lines.indexOf("Logger: com.example.app.Service");
    int tenantLine = lines.indexOf("tenant: acme");
    int firstCauseLine = indexOfFirstStartingWith(lines, "Caused by: ");

    assertThat(loggerLine).isNotNegative();
    assertThat(firstCauseLine).isNotNegative();
    // Context sits between the logger and the cause chain: PayloadTruncator keeps whole lines from
    // the START, so a correlation id survives budget pressure that costs the 5th stack frame.
    assertThat(tenantLine).isGreaterThan(loggerLine).isLessThan(firstCauseLine);
  }

  @Test
  void mdcLines_followConfiguredOrder_notMapOrder(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put("tenant", "acme"); // MDC order: tenant, correlationId
    mdc.put("correlationId", "c-42");

    // Configured order is the REVERSE of the MDC's own iteration order.
    List<String> lines =
        publishedBodyLines(
            engine(wm), eventWith(MdcProjector.project(List.of("correlationId", "tenant"), mdc::get)));

    assertThat(lines.indexOf("correlationId: c-42")).isNotNegative();
    assertThat(lines.indexOf("correlationId: c-42")).isLessThan(lines.indexOf("tenant: acme"));
  }

  @Test
  void withoutIncludeMdcKeys_bodyIsByteIdenticalToTheOldLayout(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    AlertEngine engine = engine(wm);
    engine.start();
    engine.submit(eventWith(Map.of()));
    engine.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    // Byte-for-byte the pre-feature layout: no blank line, no separator, nothing at all where the
    // context block would go. This is the regression guard for every existing user.
    assertThat(requests.get(0).getBodyAsString())
        .isEqualTo(
            "Message: boom\n"
                + "Logger: com.example.app.Service\n"
                + "Caused by: java.lang.IllegalStateException: bad\n"
                + "  at com.example.app.Service.run(Service.java:1)\n"
                + "Time: 1970-01-01T00:00:00Z");
  }

  private static int indexOfFirstStartingWith(List<String> lines, String prefix) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }
}
