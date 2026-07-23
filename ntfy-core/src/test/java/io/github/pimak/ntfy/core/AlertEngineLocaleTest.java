package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Proves the configured locale threads all the way from {@link NtfyConfig} through {@link
 * AlertEngine#start()} into the emitted diagnostics: a French-configured engine produces the French
 * status text, while the default engine stays English. This is the end-to-end complement to the
 * bundle-level checks in {@link AlertMessagesBundleSafetyTest}.
 */
class AlertEngineLocaleTest {

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
  void frenchLocale_emitsFrenchInvalidUrlDiagnostic() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("ntfy.sh") // no scheme -> invalid endpoint, refuses activation
                .topic("alerts")
                .locale(Locale.FRENCH)
                .build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    String frenchInvalidUrl = AlertMessages.forLocale(Locale.FRENCH).statusInvalidUrl();
    assertThat(diagnostics.warns).contains(frenchInvalidUrl);
    // Sanity: the French text really is different from the English default.
    assertThat(frenchInvalidUrl)
        .isNotEqualTo(AlertMessages.forLocale(Locale.ENGLISH).statusInvalidUrl());
  }

  @Test
  void defaultLocale_emitsEnglishInvalidUrlDiagnostic() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder().url("ntfy.sh").topic("alerts").build(), diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns)
        .contains(AlertMessages.forLocale(Locale.ENGLISH).statusInvalidUrl());
  }

  @Test
  void unshippedLocale_fallsBackToEnglishDiagnostic() {
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("ntfy.sh")
                .topic("alerts")
                .locale(Locale.forLanguageTag("ja"))
                .build(),
            diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns)
        .contains(AlertMessages.forLocale(Locale.ENGLISH).statusInvalidUrl());
  }
}
