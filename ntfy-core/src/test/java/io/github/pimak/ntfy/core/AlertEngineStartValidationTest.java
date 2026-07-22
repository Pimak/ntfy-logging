package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the start()-time security validations: an invalid topic refuses activation, credentials
 * over plain http warn loudly, the ACTIVE diagnostic never leaks a password fragment — even through
 * the unencoded-{@code @}-in-password edge case — and an invalid connect/request timeout falls back
 * to the default (loudly) instead of throwing out of {@code start()} with resources half-acquired.
 */
class AlertEngineStartValidationTest {

  private static final class CapturingDiagnostics implements Diagnostics {
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

  @Test
  void invalidTopic_refusesActivationWithWarning() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("https://ntfy.example.com").topic("a/../b").build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_TOPIC);
  }

  @Test
  void noSchemeUrl_refusesActivationWithWarning() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("ntfy.sh").topic("alerts").build(), diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_URL);
  }

  @Test
  void nonHttpScheme_refusesActivation() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("ftp://ntfy.example.com").topic("alerts").build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_URL);
  }

  @Test
  void unparseableUrl_refusesActivation() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("not a url").topic("alerts").build(), diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_URL);
  }

  @Test
  void whitespacePaddedUrl_refusesActivation() {
    // Pins the no-trim decision: the publisher never trims, so " https://..." fails URI parsing on
    // every publish. Activation must be refused loudly, not deferred to per-publish generic
    // failures.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url(" https://ntfy.example.com").topic("alerts").build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_URL);
  }

  @Test
  void reverseProxyPathUrl_activates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("https://host.example.com/ntfy").topic("alerts").build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).doesNotContain(AlertMessages.STATUS_INVALID_URL);
    } finally {
      engine.stop();
    }
  }

  @Test
  void nonStandardPortUrl_activates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("https://ntfy.example.com:8443").topic("alerts").build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).doesNotContain(AlertMessages.STATUS_INVALID_URL);
    } finally {
      engine.stop();
    }
  }

  @Test
  void trailingSlashUrl_activates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("https://ntfy.sh/").topic("alerts").build(), diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).doesNotContain(AlertMessages.STATUS_INVALID_URL);
    } finally {
      engine.stop();
    }
  }

  @Test
  void userinfoUrl_activatesAndDoesNotTripUrlValidation() {
    // Regression guard for the getAuthority()-not-getHost() choice: URI.getHost() is null for a
    // user:pass@host URL, so a host-based rule would over-reject this already-supported form. Over
    // https there must be no cleartext warning either.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://user:pass@ntfy.example.com")
                .topic("alerts")
                .token("tk_secret")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).doesNotContain(AlertMessages.STATUS_INVALID_URL);
      assertThat(diagnostics.warns)
          .doesNotContain(AlertMessages.STATUS_CREDENTIALS_OVER_PLAIN_HTTP);
    } finally {
      engine.stop();
    }
  }

  @Test
  void statusInvalidUrl_isFixedTextAndNeverEchoesTheRejectedUrl() {
    // No-leak discipline: the message is a fixed constant and never interpolates the rejected URL
    // (which could carry a credential in a user:pass@host form).
    assertThat(AlertMessages.STATUS_INVALID_URL)
        .isEqualTo(
            "url is not a valid http(s) endpoint (expected http:// or https:// with a host) "
                + "— engine disabled");
  }

  @Test
  void credentialsOverPlainHttp_warnButStillActivate() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://ntfy.internal:8080")
                .topic("alerts")
                .token("tk_secret")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_CREDENTIALS_OVER_PLAIN_HTTP);
    } finally {
      engine.stop();
    }
  }

  @Test
  void credentialsOverHttps_doNotWarnAboutCleartext() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .token("tk_secret")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns)
          .doesNotContain(AlertMessages.STATUS_CREDENTIALS_OVER_PLAIN_HTTP);
    } finally {
      engine.stop();
    }
  }

  @Test
  void nonAsciiTagsValue_warnsOnceAtStart() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .errorTags("🚨") // a literal 🚨 emoji instead of the shortcode
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_PRIORITY_OR_TAGS);
    } finally {
      engine.stop();
    }
  }

  @Test
  void zeroConnectTimeout_warnsAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .connectTimeout(Duration.ZERO)
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_CONNECT_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void negativeConnectTimeout_warnsAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .connectTimeout(Duration.ofSeconds(-1))
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_CONNECT_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void nullConnectTimeout_warnsAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .connectTimeout(null)
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_CONNECT_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void zeroRequestTimeout_warnsAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .requestTimeout(Duration.ZERO)
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_REQUEST_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void negativeRequestTimeout_warnsAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .requestTimeout(Duration.ofSeconds(-1))
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_REQUEST_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void nullRequestTimeout_warnsAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .requestTimeout(null)
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(AlertMessages.STATUS_INVALID_REQUEST_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void validTimeouts_doNotWarn() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("https://ntfy.example.com").topic("alerts").build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns)
          .doesNotContain(AlertMessages.STATUS_INVALID_CONNECT_TIMEOUT)
          .doesNotContain(AlertMessages.STATUS_INVALID_REQUEST_TIMEOUT);
    } finally {
      engine.stop();
    }
  }

  @Test
  void invalidTimeoutWarnings_neverEmbedTheOffendingValue() {
    // Locks in the credential-safety convention: the fixed messages must never interpolate the
    // user-supplied value (e.g. the raw "-1"), matching the neighboring status constants.
    assertThat(AlertMessages.STATUS_INVALID_CONNECT_TIMEOUT).doesNotContain("-1");
    assertThat(AlertMessages.STATUS_INVALID_REQUEST_TIMEOUT).doesNotContain("-1");
  }

  @Test
  void statusActive_stripsUserinfoIncludingUnencodedAtInPassword() {
    assertThat(AlertMessages.statusActive("https://user:pass@ntfy.example.com/x", "t"))
        .isEqualTo("ntfy alert engine ACTIVE (url=https://ntfy.example.com/x, topic=t)");
    // Unencoded '@' inside the password: the strip must consume up to the LAST '@' before the
    // path, never leaving a password tail ("ss@host") in diagnostic output.
    assertThat(AlertMessages.statusActive("https://user:p@ss@ntfy.example.com/x", "t"))
        .isEqualTo("ntfy alert engine ACTIVE (url=https://ntfy.example.com/x, topic=t)");
  }
}
