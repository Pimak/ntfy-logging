package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link DurationParser#parse(String)} across every documented spelling: a bare integer as
 * milliseconds, the {@code ms/s/m/h/d} suffixes, ISO-8601 fallback, and the throw-on-unparseable
 * contract. Also pins a few surprising-but-current behaviors (signed and internally-spaced values)
 * so any future normalization is a deliberate, reviewed change rather than an accident.
 */
class DurationParserTest {

  @Test
  void bareInteger_isMilliseconds() {
    assertThat(DurationParser.parse("500")).isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void bareZero_isZeroDuration() {
    assertThat(DurationParser.parse("0")).isEqualTo(Duration.ZERO);
  }

  @ParameterizedTest
  @CsvSource({
    "5ms, PT0.005S",
    "5s, PT5S",
    "3m, PT3M",
    "2h, PT2H",
    "1d, PT24H",
  })
  void suffixedInteger_usesUnit(String input, String expectedIso) {
    assertThat(DurationParser.parse(input)).isEqualTo(Duration.parse(expectedIso));
  }

  @Test
  void iso8601_simpleSeconds_isParsed() {
    assertThat(DurationParser.parse("PT5S")).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void iso8601_compound_isParsed() {
    assertThat(DurationParser.parse("PT1H30M")).isEqualTo(Duration.ofMinutes(90));
  }

  @Test
  void surroundingWhitespace_isTrimmedBeforeMatching() {
    assertThat(DurationParser.parse("  5s  ")).isEqualTo(Duration.ofSeconds(5));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "abc", "5x", "5 apples", "5s extra", "PT"})
  void unparseableValue_throwsIllegalArgument(String input) {
    assertThatThrownBy(() -> DurationParser.parse(input))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullValue_throwsIllegalArgument() {
    assertThatThrownBy(() -> DurationParser.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bareIntegerOverflowingLong_throwsIllegalArgument() {
    // NumberFormatException is a subclass of IllegalArgumentException, so the documented
    // throws-contract still holds for an oversized bare integer.
    assertThatThrownBy(() -> DurationParser.parse("1".repeat(25)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- Documented-quirk regression locks (current behavior; see class javadoc) -------------------

  @Test
  void negativeBareInteger_isAcceptedAsNegativeMillis_currentBehavior() {
    // The BARE_INTEGER regex accepts a leading +/- sign, so a bare negative integer yields a
    // negative Duration. Locked in as current behavior; not necessarily desirable.
    assertThat(DurationParser.parse("-500")).isEqualTo(Duration.ofMillis(-500));
  }

  @Test
  void positiveSignedBareInteger_isAccepted_currentBehavior() {
    assertThat(DurationParser.parse("+500")).isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void internalWhitespaceBetweenAmountAndSuffix_isAccepted_currentBehavior() {
    // The SUFFIXED regex is "(\\d+)\\s*(ms|s|m|h|d)", so whitespace between the number and unit is
    // tolerated. Locked in as current behavior.
    assertThat(DurationParser.parse("5 s")).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void negativeSuffixedInteger_isRejected_currentBehavior() {
    // The suffix branch has no sign group, so "-5s" falls through to ISO parsing and throws --
    // asymmetric with the accepted bare "-500". Locked in as current behavior.
    assertThatThrownBy(() -> DurationParser.parse("-5s"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
