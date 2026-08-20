package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Proves a French-configured engine puts a French storm digest ON THE WIRE. The existing coverage
 * stops short of this: {@link AlertMessagesBundleSafetyTest} and {@link AlertMessagesDigestTest}
 * exercise the bundle in isolation, and {@link AlertEngineLocaleTest} follows the locale only as far
 * as a {@link Diagnostics} callback — none of them ever looks at a published request body, so a
 * translation that never reaches the HTTP layer would go unnoticed.
 *
 * <p>End-to-end in both directions that matter. The locale is set the way an operator sets it, as
 * the {@code ntfy.locale} configuration key resolved through {@link ConfigLoader} — never by
 * constructing a bundle by hand — and the assertion is on the body WireMock actually received, after
 * the burst, the rate limiter, the digest timer and the publisher have all had their say.
 */
@WireMockTest
class AlertEngineFrenchDigestIT {

  private static final String LOGGER = "com.example.app.Service";
  private static final int MAX_ALERTS_PER_WINDOW = 1;
  private static final int TOTAL_EVENTS = 4;
  private static final int EXPECTED_SUPPRESSED = TOTAL_EVENTS - MAX_ALERTS_PER_WINDOW; // 3

  /**
   * Doubles as the rate-limit window and the digest period (the engine derives both from
   * {@code suppression-window}), so it also decides the digest's own "in the last ..." phrasing.
   * 2 s is short enough to keep the test quick and long enough to be safe on a loaded CI box: a
   * max-1 allowance means the burst performs exactly ONE loopback HTTP round-trip before the
   * remaining three events are gated without any I/O at all, so the whole burst is orders of
   * magnitude shorter than the window and the allowance cannot roll over mid-burst and leak a
   * second individual publish. The value is also >= 2000 ms deliberately: it renders through the
   * PLURAL {@code window.seconds} key, whose French and English forms differ, which is what the
   * fallback guard below leans on.
   */
  private static final Duration WINDOW = Duration.ofSeconds(2);

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  /**
   * Builds the config from configuration KEYS rather than builder calls, through the same
   * {@link ConfigLoader} precedence chain a deployment goes through, so the test covers language-tag
   * parsing too. Only the system-property layer is supplied ({@code null} env and file lookups): the
   * result must never depend on the ambient environment or on a classpath {@code ntfy.properties}.
   * {@code locale} is omitted entirely when {@code languageTag} is {@code null}, which is how the
   * English default is reached — not by spelling English out.
   */
  private static NtfyConfig config(WireMockRuntimeInfo wm, String languageTag) {
    Properties props = new Properties();
    props.setProperty("ntfy.url", "http://localhost:" + wm.getHttpPort());
    props.setProperty("ntfy.topic", "alerts");
    props.setProperty("ntfy.max-alerts-per-window", String.valueOf(MAX_ALERTS_PER_WINDOW));
    props.setProperty("ntfy.suppression-window", String.valueOf(WINDOW.toMillis()));
    // Explicit 2.0 opt-out, as in AlertEngineMdcBodyIT: with the async default an individual alert
    // is still queued when stop() runs and folds into the digest instead, moving the suppressed
    // count this test pins.
    props.setProperty("ntfy.async", "false");
    if (languageTag != null) {
      props.setProperty("ntfy.locale", languageTag);
    }
    return ConfigLoader.load(null, null, props);
  }

  /**
   * Runs the burst and returns the body of the digest — the FOLLOW-UP notification, not the first
   * alert. The two are told apart by the digest tags rather than by journal position, so the test
   * cannot silently assert on the individual alert if the order ever changed.
   */
  private static String publishedDigestBody(NtfyConfig config) throws InterruptedException {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    AlertEngine engine = new AlertEngine(config, NO_OP);
    engine.start();
    assertThat(engine.isStarted()).isTrue();

    for (int i = 0; i < TOTAL_EVENTS; i++) {
      engine.submit(AlertEvent.of(LOGGER, "boom " + i, 0L));
    }

    // Poll rather than sleep out the window: the digest tick lands late on a loaded box, and a
    // fixed sleep would either flake there or pad every healthy run.
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline
        && findAll(postRequestedFor(urlEqualTo("/alerts"))).size() < MAX_ALERTS_PER_WINDOW + 1) {
      Thread.sleep(25);
    }
    engine.stop();

    verify(
        MAX_ALERTS_PER_WINDOW,
        postRequestedFor(urlEqualTo("/alerts"))
            .withHeader("Tags", equalTo(config.getErrorTags())));
    List<LoggedRequest> digests =
        findAll(
            postRequestedFor(urlEqualTo("/alerts"))
                .withHeader("Tags", equalTo(config.getDigestTags())));
    assertThat(digests).as("exactly one digest at window close").hasSize(1);
    return digests.get(0).getBodyAsString();
  }

  /**
   * The digest body the bundle produces for this burst. {@code describeWindow} takes the configured
   * window twice because its second argument is only the fallback for an invalid window, and
   * {@link #WINDOW} is valid — the engine's own default never enters the picture.
   */
  private static String expectedDigestBody(Locale locale) {
    AlertMessages messages = AlertMessages.forLocale(locale);
    return messages.digestBody(
        EXPECTED_SUPPRESSED,
        Map.of(LOGGER, EXPECTED_SUPPRESSED),
        messages.describeWindow(WINDOW, WINDOW));
  }

  @Test
  void frenchLocale_publishesTheDigestBodyInFrench(WireMockRuntimeInfo wm) throws Exception {
    String body = publishedDigestBody(config(wm, "fr"));

    assertThat(body).isEqualTo(expectedDigestBody(Locale.FRENCH));

    // The assertion above is NOT self-sufficient, and this is the whole point of the test. Both
    // sides of it read the same catalog, so if French were broken at the resource level — bundle
    // dropped from the jar, renamed, excluded from the shaded/native build — forLocale(FRENCH)
    // would fall back to the English base exactly as the engine did, both sides would say the same
    // English sentence, and the comparison would pass while not one French word reached ntfy. Only
    // pinning the wire body as DIFFERENT from the English rendering of the very same message
    // catches that: a silent fallback makes these two equal, so this line goes red.
    assertThat(body).isNotEqualTo(expectedDigestBody(Locale.ENGLISH));
  }

  /**
   * The mirror image, over the identical harness: with no {@code ntfy.locale} key the same burst
   * publishes the English digest. Together with the guard above this pins the locale knob as the
   * thing that MOVES the wire body, rather than leaving "French differs from English" as a claim
   * about the bundle that the HTTP layer never has to honor.
   */
  @Test
  void defaultLocale_publishesTheDigestBodyInEnglish(WireMockRuntimeInfo wm) throws Exception {
    String body = publishedDigestBody(config(wm, null));

    assertThat(body).isEqualTo(expectedDigestBody(Locale.ENGLISH));
    assertThat(body).isNotEqualTo(expectedDigestBody(Locale.FRENCH));
  }
}
