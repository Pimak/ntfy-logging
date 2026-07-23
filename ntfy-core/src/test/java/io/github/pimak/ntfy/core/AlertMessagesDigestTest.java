package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AlertMessages#digestTitle} and {@link AlertMessages#digestBody} produce the
 * expected digest formats and remain credential-safe. Exercised through the English-locale instance
 * so the asserted output is byte-for-byte identical to the pre-translation constants.
 */
class AlertMessagesDigestTest {

  private final AlertMessages messages = AlertMessages.forLocale(Locale.ENGLISH);

  @Test
  void digestTitle_withAppName_formatsEmDashCount() {
    String result = messages.digestTitle("MyApp", 47);

    assertThat(result).isEqualTo("MyApp — 47 errors suppressed");
  }

  @Test
  void digestTitle_withNullBase_treatsAsEmptyStringNoNpe() {
    String result = messages.digestTitle(null, 47);

    assertThat(result).isEqualTo(" — 47 errors suppressed");
  }

  @Test
  void digestBody_startsWithCountAndWindowDescription() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("com.foo.Bar", 12);

    String result = messages.digestBody(12, tally, "3 minutes");

    assertThat(result).startsWith("12 errors suppressed in the last 3 minutes");
  }

  @Test
  void digestBody_threeLoggers_noOverflowMarker() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("com.foo.Bar", 5);
    tally.put("com.foo.Baz", 3);
    tally.put("com.foo.Qux", 1);

    String result = messages.digestBody(9, tally, "3 minutes");

    assertThat(result).doesNotContain("others");
    assertThat(result).contains("5x com.foo.Bar");
    assertThat(result).contains("3x com.foo.Baz");
    assertThat(result).contains("1x com.foo.Qux");
  }

  @Test
  void digestBody_eightLoggers_topFivePlusOverflowMarker() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("logger1", 8);
    tally.put("logger2", 7);
    tally.put("logger3", 6);
    tally.put("logger4", 5);
    tally.put("logger5", 4);
    tally.put("logger6", 3);
    tally.put("logger7", 2);
    tally.put("logger8", 1);

    String result = messages.digestBody(36, tally, "3 minutes");

    long loggerLineCount =
        java.util.Arrays.stream(result.split("\n"))
            .filter(line -> line.contains("x logger"))
            .count();
    assertThat(loggerLineCount).isEqualTo(5);
    assertThat(result).contains("+3 others");
    assertThat(result).contains("8x logger1");
    assertThat(result).contains("7x logger2");
    assertThat(result).doesNotContain("1x logger8");
  }

  @Test
  void describeWindow_zeroOrNegativeDuration_describesFallbackWindowNotOneSecond() {
    java.time.Duration threeMinutes = java.time.Duration.ofMinutes(3);

    // start() clamps a zero/negative suppressionWindow to the default before scheduling, so the
    // digest must describe that clamped window — never "1 second" for a window the engine
    // never ran at.
    assertThat(messages.describeWindow(java.time.Duration.ZERO, threeMinutes))
        .isEqualTo("3 minutes");
    assertThat(messages.describeWindow(java.time.Duration.ofSeconds(-5), threeMinutes))
        .isEqualTo("3 minutes");
    assertThat(messages.describeWindow(null, threeMinutes)).isEqualTo("3 minutes");
  }

  @Test
  void digestBody_neverContainsCredentialFields() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("com.foo.Bar", 1);

    String title = messages.digestTitle("MyApp", 1);
    String body = messages.digestBody(1, tally, "3 minutes");

    assertThat(title.toLowerCase()).doesNotContain("token").doesNotContain("password");
    assertThat(body.toLowerCase())
        .doesNotContain("token")
        .doesNotContain("username")
        .doesNotContain("password");
  }
}
