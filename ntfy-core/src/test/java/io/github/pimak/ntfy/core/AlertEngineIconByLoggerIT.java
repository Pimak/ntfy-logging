package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The per-logger icon table: which icon a given alert ends up carrying.
 *
 * <p>A table rather than placeholders in the URL, and that is a security property rather than a
 * style choice. The subscriber's client downloads the icon automatically when it DISPLAYS the
 * notification — no tap required — so an interpolated URL would leak whatever it interpolated, plus
 * the subscriber's IP, to a third-party host on every single notification. A table only ever
 * selects among URLs the operator wrote out in full.
 */
@WireMockTest
class AlertEngineIconByLoggerIT {

  private static final String DEFAULT_ICON = "https://cdn.example.com/default.png";
  private static final String BILLING_ICON = "https://cdn.example.com/billing.png";
  private static final String INVOICES_ICON = "https://cdn.example.com/invoices.png";

  private static final class RecordingDiagnostics implements Diagnostics {
    final List<String> warns = new ArrayList<>();

    @Override
    public void info(String msg) {}

    @Override
    public void warn(String msg) {
      warns.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {}
  }

  private static Map<String, String> table() {
    Map<String, String> icons = new LinkedHashMap<>();
    icons.put("com.acme.billing", BILLING_ICON);
    icons.put("com.acme.billing.invoices", INVOICES_ICON);
    return icons;
  }

  private record Published(LoggedRequest request, List<String> warns) {}

  private static Published alertFrom(
      WireMockRuntimeInfo wm, String loggerName, Map<String, String> icons) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .icon(DEFAULT_ICON)
            .iconsByLoggerPrefix(icons)
            .asyncEnabled(false)
            .build();

    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine = new AlertEngine(config, diagnostics);
    engine.start();
    engine.submit(AlertEvent.of(loggerName, "boom", 0L));
    engine.stop();

    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests).isNotEmpty();
    return new Published(requests.get(requests.size() - 1), diagnostics.warns);
  }

  @Test
  void aMatchingPrefixOverridesTheDefault(WireMockRuntimeInfo wm) {
    assertThat(alertFrom(wm, "com.acme.billing.Ledger", table()).request().getHeader("Icon"))
        .isEqualTo(BILLING_ICON);
  }

  @Test
  void theLongestMatchingPrefixWins(WireMockRuntimeInfo wm) {
    // Both "com.acme.billing" and "com.acme.billing.invoices" match; the more specific one is the
    // one the operator meant.
    assertThat(
            alertFrom(wm, "com.acme.billing.invoices.Dunning", table()).request().getHeader("Icon"))
        .isEqualTo(INVOICES_ICON);
  }

  @Test
  void aLoggerMatchingNothingFallsBackToTheDefault(WireMockRuntimeInfo wm) {
    assertThat(alertFrom(wm, "com.acme.shipping.Dispatch", table()).request().getHeader("Icon"))
        .isEqualTo(DEFAULT_ICON);
  }

  @Test
  void matchingRespectsTheHierarchyBoundary(WireMockRuntimeInfo wm) {
    // A bare startsWith would give the billing icon to the unrelated sibling package
    // "com.acme.billingsystem".
    assertThat(
            alertFrom(wm, "com.acme.billingsystem.Runner", table()).request().getHeader("Icon"))
        .isEqualTo(DEFAULT_ICON);
  }

  @Test
  void anExactLoggerNameMatchesItsOwnPrefix(WireMockRuntimeInfo wm) {
    assertThat(alertFrom(wm, "com.acme.billing", table()).request().getHeader("Icon"))
        .isEqualTo(BILLING_ICON);
  }

  @Test
  void anUnusableEntryIsDroppedAtStartupWithoutTakingTheRestWithIt(WireMockRuntimeInfo wm) {
    Map<String, String> icons = new LinkedHashMap<>();
    icons.put("com.acme.billing", "https://cdn.example.com/billing.svg"); // ntfy renders neither
    icons.put("com.acme.shipping", BILLING_ICON);

    Published billing = alertFrom(wm, "com.acme.billing.Ledger", icons);
    assertThat(billing.request().getHeader("Icon")).isEqualTo(DEFAULT_ICON);
    assertThat(billing.warns()).anySatisfy(w -> assertThat(w).contains("icon"));

    assertThat(alertFrom(wm, "com.acme.shipping.Dispatch", icons).request().getHeader("Icon"))
        .isEqualTo(BILLING_ICON);
  }

  @Test
  void aPairThatIsNotPrefixEqualsUrlIsReportedRatherThanSwallowed(WireMockRuntimeInfo wm) {
    // A space where the = should be would otherwise look exactly like an unset key: no icon,
    // no error, nothing to notice. That is the invisible failure the URL check exists to
    // prevent, and it must not sneak back in one layer earlier, at parse time.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .icon(DEFAULT_ICON)
            .iconsByLoggerPrefixCsv("com.acme.billing https://cdn.example.com/billing.png")
            .asyncEnabled(false)
            .build();

    assertThat(config.isIconsByLoggerValueRejected()).isTrue();

    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine = new AlertEngine(config, diagnostics);
    engine.start();
    engine.stop();

    assertThat(diagnostics.warns).anySatisfy(w -> assertThat(w).contains("icon"));
  }

  @Test
  void aWellFormedCsvPairIsNotReportedAsRejected(WireMockRuntimeInfo wm) {
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .iconsByLoggerPrefixCsv("com.acme.billing=" + BILLING_ICON)
            .build();

    assertThat(config.isIconsByLoggerValueRejected()).isFalse();
    assertThat(config.getIconsByLoggerPrefix())
        .containsExactly(java.util.Map.entry("com.acme.billing", BILLING_ICON));
  }

  @Test
  void anEmptyTableLeavesTheDefaultAlone(WireMockRuntimeInfo wm) {
    Published published = alertFrom(wm, "com.acme.billing.Ledger", Map.of());

    assertThat(published.request().getHeader("Icon")).isEqualTo(DEFAULT_ICON);
    assertThat(published.warns()).noneSatisfy(w -> assertThat(w).contains("icon"));
  }
}
