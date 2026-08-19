package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The diagnostic half of the feature: an operator must be told the probable CAUSE and the fix, not
 * just that something failed. Each HTTP status maps to a distinct, actionable message, and no
 * message may ever echo a credential.
 */
@WireMockTest
class AlertEngineStartupPingDiagnosticsTest {

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

  private CapturingDiagnostics runProbeAgainstStatus(WireMockRuntimeInfo wm, int status)
      throws InterruptedException {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(status)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .startupPing(StartupPingMode.PROBE)
                .build(),
            diag);
    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();
    return diag;
  }

  @ParameterizedTest(name = "HTTP {0} warns with the catalog''s diagnosis and never reports success")
  @ValueSource(ints = {401, 403, 404, 429, 500, 503, 400})
  void everyNonSuccessStatusWarnsAndNeverPasses(int status, WireMockRuntimeInfo wm)
      throws Exception {
    CapturingDiagnostics diag = runProbeAgainstStatus(wm, status);

    assertThat(diag.warns).contains(messages.statusStartupPingFailed(status, null));
    assertThat(diag.infos).noneMatch(s -> s.contains("startup self-test"));
  }

  /**
   * The classification is only useful if the classes actually read differently — a mapping that
   * collapsed every status onto one generic string would satisfy the per-status test above while
   * telling the operator nothing.
   */
  @Test
  void theFourFailureClassesProduceFourDistinctMessages() {
    String credentials = messages.statusStartupPingFailed(401, null);
    String notFound = messages.statusStartupPingFailed(404, null);
    String rateLimited = messages.statusStartupPingFailed(429, null);
    String serverError = messages.statusStartupPingFailed(500, null);
    String unreachable = messages.statusStartupPingFailed(null, "timeout");

    assertThat(List.of(credentials, notFound, rateLimited, serverError, unreachable))
        .doesNotHaveDuplicates();
    // 403 and 503 share the credentials / server-error TEMPLATE with 401 and 500 respectively, but
    // each still reports its own status code rather than a hardcoded one.
    assertThat(messages.statusStartupPingFailed(403, null)).contains("403").contains("credentials");
    assertThat(messages.statusStartupPingFailed(503, null)).contains("503").contains("internal error");
  }

  @Test
  void unauthorizedNamesTheRevokedTokenCause_theWholePointOfTheFeature(WireMockRuntimeInfo wm)
      throws Exception {
    CapturingDiagnostics diag = runProbeAgainstStatus(wm, 401);

    // The message must be self-sufficient: an operator reading it should not have to go look up
    // what 401 means for ntfy.
    assertThat(diag.warns)
        .anySatisfy(
            w ->
                assertThat(w)
                    .contains("401")
                    .contains("rejected the credentials")
                    .contains("revoked or expired"));
  }

  @Test
  void notFoundPointsAtTheBaseUrlMistake(WireMockRuntimeInfo wm) throws Exception {
    CapturingDiagnostics diag = runProbeAgainstStatus(wm, 404);

    // Appending the topic to `url` is the single most common ntfy misconfiguration; say so.
    assertThat(diag.warns).anySatisfy(w -> assertThat(w).contains("404").contains("BASE url"));
  }

  @Test
  void serverErrorAbsolvesTheConfiguration(WireMockRuntimeInfo wm) throws Exception {
    CapturingDiagnostics diag = runProbeAgainstStatus(wm, 503);

    assertThat(diag.warns)
        .anySatisfy(w -> assertThat(w).contains("503").contains("configuration itself looks correct"));
  }

  @Test
  void unreachableServerIsDiagnosedWithoutAnyHttpStatus() throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                // Port 1 is reserved and never listening: a connection failure, not an HTTP error.
                .url("http://localhost:1")
                .topic("alerts")
                .startupPing(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();

    assertThat(diag.warns)
        .anySatisfy(
            w -> assertThat(w).contains("could not be reached").contains("firewall or proxy"));
  }

  @Test
  void noDiagnosticEverEchoesTheConfiguredCredentials(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(401)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .token("tk_supersecret_value")
                .username("alice")
                .password("hunter2")
                .requireHttpsForCredentials(false)
                .startupPing(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();

    // A 401 diagnostic is exactly where a naive implementation would helpfully print the token it
    // tried. Every emitted line must be free of all three secrets.
    assertThat(diag.infos).allSatisfy(AlertEngineStartupPingDiagnosticsTest::assertCredentialFree);
    assertThat(diag.warns).allSatisfy(AlertEngineStartupPingDiagnosticsTest::assertCredentialFree);
  }

  private static void assertCredentialFree(String line) {
    assertThat(line)
        .doesNotContain("tk_supersecret_value")
        .doesNotContain("hunter2")
        .doesNotContain("alice");
  }

  @Test
  void invalidModeValueIsWarnedAbout_notSilentlyTreatedAsOff(WireMockRuntimeInfo wm)
      throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .startupPing("prob") // typo for "probe"
                .build(),
            diag);

    engine.start();
    Thread.sleep(150);
    engine.stop();

    // Silently behaving as `off` here would be the very failure mode this feature exists to kill.
    assertThat(diag.warns).contains(messages.statusStartupPingInvalidMode());
  }

  @Test
  void anInvalidValueWarnsWithoutClaimingTheSelfTestWasCancelled(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    // A valid mode set first, then an unparseable one: the builder keeps the last GOOD value rather
    // than silently downgrading an explicitly-requested probe to off, so the self-test still runs.
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .startupPing(StartupPingMode.PROBE)
                .startupPing("prob")
                .build(),
            diag);

    engine.start();
    awaitSelfTestReported(diag);
    engine.stop();

    // Both must hold: the typo is reported, AND the probe really did run. The warning therefore
    // must not promise that nothing will happen — it only states that the value was ignored.
    assertThat(diag.warns).contains(messages.statusStartupPingInvalidMode());
    assertThat(diag.infos).contains(messages.statusStartupPingProbePassed());
    assertThat(messages.statusStartupPingInvalidMode())
        .doesNotContain("no self-test will run")
        .doesNotContain("keeping the default");
  }

  @Test
  void failFastWithoutAModeIsWarnedAbout(WireMockRuntimeInfo wm) throws Exception {
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .startupPingFailFast(true) // but startup-ping stays off
                .build(),
            diag);

    engine.start();
    engine.stop();

    assertThat(diag.warns).contains(messages.statusStartupPingFailFastWithoutMode());
  }

  @Test
  void frenchLocaleTranslatesTheFailureDiagnostic(WireMockRuntimeInfo wm) throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(401)));
    CapturingDiagnostics diag = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .locale("fr")
                .startupPing(StartupPingMode.PROBE)
                .build(),
            diag);

    engine.start();
    long deadline = System.currentTimeMillis() + 3000;
    while (diag.warns.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    engine.stop();

    AlertMessages fr = AlertMessages.forLocale(Locale.forLanguageTag("fr"));
    assertThat(diag.warns).contains(fr.statusStartupPingFailed(401, null));
    // MessageFormat quote-doubling is easy to get wrong in French ("l''auto-test"); a broken
    // pattern would render a stray quote or swallow the apostrophe entirely.
    assertThat(diag.warns).anySatisfy(w -> assertThat(w).contains("l'auto-test"));
  }
}
