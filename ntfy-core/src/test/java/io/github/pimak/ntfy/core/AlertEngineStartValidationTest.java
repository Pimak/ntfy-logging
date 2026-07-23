package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Covers the start()-time security validations: an invalid topic refuses activation, credentials
 * over plain http warn loudly, the ACTIVE diagnostic never leaks a password fragment — even through
 * the unencoded-{@code @}-in-password edge case — and an invalid connect/request timeout falls back
 * to the default (loudly) instead of throwing out of {@code start()} with resources half-acquired.
 */
class AlertEngineStartValidationTest {

  // English-locale catalog: the emitted diagnostics use the default English locale, so the expected
  // text is byte-for-byte identical to the pre-translation constants.
  private final AlertMessages messages = AlertMessages.forLocale(Locale.ENGLISH);

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
    assertThat(diagnostics.warns).contains(messages.statusInvalidTopic());
  }

  @Test
  void noSchemeUrl_refusesActivationWithWarning() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("ntfy.sh").topic("alerts").build(), diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(messages.statusInvalidUrl());
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
    assertThat(diagnostics.warns).contains(messages.statusInvalidUrl());
  }

  @Test
  void unparseableUrl_refusesActivation() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("not a url").topic("alerts").build(), diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns).contains(messages.statusInvalidUrl());
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
    assertThat(diagnostics.warns).contains(messages.statusInvalidUrl());
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
      assertThat(diagnostics.warns).doesNotContain(messages.statusInvalidUrl());
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
      assertThat(diagnostics.warns).doesNotContain(messages.statusInvalidUrl());
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
      assertThat(diagnostics.warns).doesNotContain(messages.statusInvalidUrl());
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
      assertThat(diagnostics.warns).doesNotContain(messages.statusInvalidUrl());
      assertThat(diagnostics.warns)
          .doesNotContain(messages.statusCredentialsOverPlainHttp());
    } finally {
      engine.stop();
    }
  }

  @Test
  void statusInvalidUrl_isFixedTextAndNeverEchoesTheRejectedUrl() {
    // No-leak discipline: the message is a fixed constant and never interpolates the rejected URL
    // (which could carry a credential in a user:pass@host form).
    assertThat(messages.statusInvalidUrl())
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
      assertThat(diagnostics.warns).contains(messages.statusCredentialsOverPlainHttp());
    } finally {
      engine.stop();
    }
  }

  @Test
  void userinfoInUrlOverPlainHttp_warnsEvenWithoutConfiguredCredentials() {
    // Gap (a) regression: http://user:pass@host embeds a secret in the request target even when no
    // token/username/password is configured — the cleartext warning must fire, and default mode
    // still activates.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://user:pass@ntfy.internal:8080")
                .topic("alerts")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(messages.statusCredentialsOverPlainHttp());
    } finally {
      engine.stop();
    }
  }

  @Test
  void plainHttpWithAtInQuery_isNotMisreadAsCredentials() {
    // urlHasUserinfo must terminate the authority at the first '/', '?', or '#'. A query/fragment
    // '@' (e.g. an email in a query param) is NOT userinfo, so with no configured credentials the
    // cleartext-credential warning must NOT fire and the engine activates normally.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://ntfy.internal:8080?email=a@b.com")
                .topic("alerts")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns)
          .doesNotContain(messages.statusCredentialsOverPlainHttp());
    } finally {
      engine.stop();
    }
  }

  @Test
  void requireHttpsForCredentials_refusesActivationForTokenOverPlainHttp() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://ntfy.internal:8080")
                .topic("alerts")
                .token("tk_secret")
                .requireHttpsForCredentials(true)
                .build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns)
        .contains(messages.statusCredentialsOverPlainHttpRefused());
  }

  @Test
  void requireHttpsForCredentials_refusesActivationForUserinfoUrlOverPlainHttp() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://user:pass@ntfy.internal:8080")
                .topic("alerts")
                .requireHttpsForCredentials(true)
                .build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns)
        .contains(messages.statusCredentialsOverPlainHttpRefused());
  }

  @Test
  void requireHttpsForCredentials_overHttps_startsNormally() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .token("tk_secret")
                .requireHttpsForCredentials(true)
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns)
          .doesNotContain(messages.statusCredentialsOverPlainHttp())
          .doesNotContain(messages.statusCredentialsOverPlainHttpRefused());
    } finally {
      engine.stop();
    }
  }

  @Test
  void requireHttpsForCredentials_noCredentialsOverPlainHttp_startsNormally() {
    // Strict mode only gates CREDENTIALS over cleartext — a credential-free http:// endpoint
    // remains a valid, first-class configuration.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("http://ntfy.internal:8080")
                .topic("alerts")
                .requireHttpsForCredentials(true)
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns)
          .doesNotContain(messages.statusCredentialsOverPlainHttp())
          .doesNotContain(messages.statusCredentialsOverPlainHttpRefused());
    } finally {
      engine.stop();
    }
  }

  @Test
  void cleartextCredentialWarnings_areFixedTextWithoutSampleCredentials() {
    // No-leak pin: both cleartext-credential messages are fixed, argument-less bundle keys that
    // never interpolate (or even resemble) a credential or the offending URL.
    assertThat(messages.statusCredentialsOverPlainHttp())
        .doesNotContain("tk_")
        .doesNotContain("user:pass")
        .doesNotContain("@");
    assertThat(messages.statusCredentialsOverPlainHttpRefused())
        .doesNotContain("tk_")
        .doesNotContain("user:pass")
        .doesNotContain("@")
        .contains("engine disabled");
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
          .doesNotContain(messages.statusCredentialsOverPlainHttp());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidPriorityOrTags());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidConnectTimeout());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidConnectTimeout());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidConnectTimeout());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidRequestTimeout());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidRequestTimeout());
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
      assertThat(diagnostics.warns).contains(messages.statusInvalidRequestTimeout());
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
          .doesNotContain(messages.statusInvalidConnectTimeout())
          .doesNotContain(messages.statusInvalidRequestTimeout());
    } finally {
      engine.stop();
    }
  }

  @Test
  void usernameOnly_warnsIncompleteBasicAuthAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .username("alice")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(messages.statusIncompleteBasicAuth());
    } finally {
      engine.stop();
    }
  }

  @Test
  void passwordOnly_warnsIncompleteBasicAuthAndStillActivates() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .password("s3cret")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).contains(messages.statusIncompleteBasicAuth());
    } finally {
      engine.stop();
    }
  }

  @Test
  void completeBasicAuthPair_doesNotWarnIncomplete() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .username("alice")
                .password("s3cret")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).doesNotContain(messages.statusIncompleteBasicAuth());
    } finally {
      engine.stop();
    }
  }

  @Test
  void tokenWithOnlyOneBasicHalf_doesNotWarnIncomplete() {
    // A usable token supersedes basic auth entirely, so the half-configured pair is not a silent
    // downgrade to None — the incomplete-basic warning would only add noise.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("alerts")
                .token("tk_secret")
                .username("alice")
                .build(),
            diagnostics);
    try {
      engine.start();

      assertThat(engine.isStarted()).isTrue();
      assertThat(diagnostics.warns).doesNotContain(messages.statusIncompleteBasicAuth());
    } finally {
      engine.stop();
    }
  }

  @Test
  void incompleteBasicAuthWarning_isFixedTextAndNeverEmbedsCredentials() {
    // No-leak discipline: the message is a fixed constant and never interpolates the configured
    // username or password, matching the neighboring status constants.
    assertThat(messages.statusIncompleteBasicAuth())
        .isEqualTo(
            "username or password set but not both — basic auth is incomplete; publishing "
                + "WITHOUT an Authorization header")
        .doesNotContain("alice")
        .doesNotContain("s3cret");
  }

  @Test
  void invalidTimeoutWarnings_neverEmbedTheOffendingValue() {
    // Locks in the credential-safety convention: the fixed messages must never interpolate the
    // user-supplied value (e.g. the raw "-1"), matching the neighboring status constants.
    assertThat(messages.statusInvalidConnectTimeout()).doesNotContain("-1");
    assertThat(messages.statusInvalidRequestTimeout()).doesNotContain("-1");
  }

  @Test
  void statusActive_stripsUserinfoIncludingUnencodedAtInPassword() {
    assertThat(messages.statusActive("https://user:pass@ntfy.example.com/x", "t"))
        .isEqualTo("ntfy alert engine ACTIVE (url=https://ntfy.example.com/x, topic=t)");
    // Unencoded '@' inside the password: the strip must consume up to the LAST '@' before the
    // path, never leaving a password tail ("ss@host") in diagnostic output.
    assertThat(messages.statusActive("https://user:p@ss@ntfy.example.com/x", "t"))
        .isEqualTo("ntfy alert engine ACTIVE (url=https://ntfy.example.com/x, topic=t)");
  }

  @Test
  void statusActive_doesNotOverStripQueryAtWithoutPath() {
    // A '@' in a query (not userinfo) must not be mistaken for credentials: with no path slash, the
    // strip must still terminate the authority at '?'/'#' and leave the URL intact rather than
    // mangling it to "http://b.com".
    assertThat(messages.statusActive("http://ntfy.internal:8080?email=a@b.com", "t"))
        .isEqualTo(
            "ntfy alert engine ACTIVE (url=http://ntfy.internal:8080?email=a@b.com, topic=t)");
    // Real userinfo alongside a later query '@' is still stripped, and the query is preserved.
    assertThat(messages.statusActive("http://user:pass@ntfy.internal:8080?email=a@b.com", "t"))
        .isEqualTo(
            "ntfy alert engine ACTIVE (url=http://ntfy.internal:8080?email=a@b.com, topic=t)");
  }
}
