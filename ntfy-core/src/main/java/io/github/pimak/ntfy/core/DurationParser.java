package io.github.pimak.ntfy.core;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the several duration spellings the ntfy configuration surface accepts into a {@link
 * Duration}. Framework-neutral replacement for the old Logback {@code Duration} coercion:
 *
 * <ul>
 *   <li>a bare integer is interpreted as <strong>milliseconds</strong> ({@code "500"} =&gt; 500ms);
 *   <li>a suffixed integer uses the unit suffix: {@code ms}, {@code s}, {@code m}, {@code h},
 *       {@code d} ({@code "5s"}, {@code "3m"}, {@code "2h"}, {@code "1d"});
 *   <li>an ISO-8601 duration is parsed by {@link Duration#parse} ({@code "PT5S"}).
 * </ul>
 *
 * <p>An unparseable value throws {@link IllegalArgumentException}.
 */
public final class DurationParser {

  private static final Pattern BARE_INTEGER = Pattern.compile("[+-]?\\d+");
  private static final Pattern SUFFIXED = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

  private DurationParser() {}

  /**
   * Parses {@code value} into a {@link Duration}. See the class javadoc for the accepted forms.
   *
   * @throws IllegalArgumentException if {@code value} is {@code null}, blank, or unparseable
   */
  public static Duration parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("duration value must not be null");
    }
    String v = value.trim();
    if (v.isEmpty()) {
      throw new IllegalArgumentException("duration value must not be blank");
    }
    if (BARE_INTEGER.matcher(v).matches()) {
      return Duration.ofMillis(Long.parseLong(v));
    }
    Matcher suffixed = SUFFIXED.matcher(v);
    if (suffixed.matches()) {
      long amount = Long.parseLong(suffixed.group(1));
      return switch (suffixed.group(2)) {
        case "ms" -> Duration.ofMillis(amount);
        case "s" -> Duration.ofSeconds(amount);
        case "m" -> Duration.ofMinutes(amount);
        case "h" -> Duration.ofHours(amount);
        case "d" -> Duration.ofDays(amount);
        default -> throw new IllegalArgumentException("unparseable duration: " + value);
      };
    }
    try {
      return Duration.parse(v);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("unparseable duration: " + value, e);
    }
  }
}
