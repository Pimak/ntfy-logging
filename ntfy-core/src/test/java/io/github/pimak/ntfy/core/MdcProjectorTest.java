package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Locks the safety contract of the {@code include-mdc-keys} projection: an EXPLICIT allow-list (no
 * wildcard, nothing published unless named), configured rendering order, and per-entry scrubbing,
 * truncation, and capping so no MDC value can forge body structure, hide behind bidi/zero-width
 * characters, or eat the alert body's byte budget.
 */
class MdcProjectorTest {

  /** A lookup that must never run: any call fails the test with an {@link AssertionError}. */
  private static final Function<String, String> EXPLODING_LOOKUP =
      key -> {
        // Deliberately an Error, not a RuntimeException: project() swallows RuntimeException by
        // design, so only an Error can prove the lookup was never consulted in the first place.
        throw new AssertionError("the MDC must not be consulted at all, but was asked for: " + key);
      };

  private static Function<String, String> lookup(Map<String, String> mdc) {
    return mdc::get;
  }

  private static String repeat(char c, int count) {
    return String.valueOf(c).repeat(count);
  }

  // --- The allow-list is the gate ---------------------------------------------------------------

  @Test
  void emptyAllowList_returnsEmptyMap_andNeverConsultsTheMdc() {
    assertThat(MdcProjector.project(null, EXPLODING_LOOKUP)).isEmpty();
    assertThat(MdcProjector.project(List.of(), EXPLODING_LOOKUP)).isEmpty();
    // A null lookup (framework with no MDC at all) is the same fast path.
    assertThat(MdcProjector.project(List.of("tenant"), null)).isEmpty();
  }

  @Test
  void rendersOnlyAllowListedKeys_inConfiguredOrder() {
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put("a", "1");
    mdc.put("b", "2");
    mdc.put("c", "3");

    Map<String, String> projected = MdcProjector.project(List.of("b", "a"), lookup(mdc));

    // Configured order wins over the MDC's own iteration order, and 'c' — never named — is absent.
    assertThat(projected).containsExactly(Map.entry("b", "2"), Map.entry("a", "1"));
    assertThat(projected).doesNotContainKey("c");
  }

  @Test
  void keyAbsentFromMdc_isOmittedEntirely() {
    Map<String, String> mdc = Map.of("tenant", "acme");

    Map<String, String> projected =
        MdcProjector.project(List.of("tenant", "requestId"), lookup(mdc));

    // No entry at all, so buildBody renders no line — never an empty "requestId: " line.
    assertThat(projected).containsExactly(Map.entry("tenant", "acme"));
  }

  @Test
  void blankOrControlOnlyValue_isOmitted() {
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put("empty", "");
    mdc.put("spaces", "   ");
    mdc.put("controlOnly", "\n\t\u202e"); // control chars plus a bidi override, nothing else
    mdc.put("real", "value");

    Map<String, String> projected =
        MdcProjector.project(List.of("empty", "spaces", "controlOnly", "real"), lookup(mdc));

    assertThat(projected).containsExactly(Map.entry("real", "value"));
  }

  @Test
  void duplicateKeysInAllowList_areDeduped() {
    Map<String, String> mdc = Map.of("tenant", "acme");

    Map<String, String> projected =
        MdcProjector.project(List.of("tenant", "tenant", "tenant"), lookup(mdc));

    assertThat(projected).hasSize(1).containsEntry("tenant", "acme");
  }

  @Test
  void lookupThrowing_isTreatedAsAbsent() {
    Function<String, String> hostile =
        key -> {
          if ("boom".equals(key)) {
            throw new IllegalStateException("hostile MDC implementation");
          }
          return "ok";
        };

    Map<String, String> projected = MdcProjector.project(List.of("boom", "fine"), hostile);

    // A broken context implementation degrades to "key absent" and never breaks the logging path.
    assertThat(projected).containsExactly(Map.entry("fine", "ok"));
  }

  // --- Scrubbing ---------------------------------------------------------------------------------

  @Test
  void newlinesAndControlChars_areNeutralized() {
    Map<String, String> mdc = Map.of("tenant", "acme\nCaused by: java.lang.Forged");

    Map<String, String> projected = MdcProjector.project(List.of("tenant"), lookup(mdc));

    String value = projected.get("tenant");
    // No newline survives: a value can neither forge a body line nor break PayloadTruncator's
    // line-by-line splitting. The text itself is kept (replaced by a space, never deleted).
    assertThat(value).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
    assertThat(value).isEqualTo("acme Caused by: java.lang.Forged");
  }

  @Test
  void bidiAndUnicodeSeparators_areNeutralized() {
    Map<String, String> mdc = new LinkedHashMap<>();
    // U+202E RIGHT-TO-LEFT OVERRIDE + U+200D ZERO WIDTH JOINER (both category Cf).
    mdc.put("bidi", "a\u202eb\u200dc");
    // U+2028/U+2029 are not ISO controls but are line breaks to plenty of renderers.
    mdc.put("separators", "x\u2028y\u2029z");

    Map<String, String> projected =
        MdcProjector.project(List.of("bidi", "separators"), lookup(mdc));

    assertThat(projected.get("bidi")).isEqualTo("a b c");
    assertThat(projected.get("separators")).isEqualTo("x y z");
  }

  // --- Truncation -------------------------------------------------------------------------------

  @Test
  void valueOverCap_isTruncatedWithEllipsis_andExactly256Chars() {
    Map<String, String> mdc = Map.of("big", repeat('a', 1000));

    String value = MdcProjector.project(List.of("big"), lookup(mdc)).get("big");

    // Exactly MAX_VALUE_CHARS including the marker: that exactness is what makes sanitize()
    // idempotent (a second pass sees 256 > 256 == false).
    assertThat(value).hasSize(MdcProjector.MAX_VALUE_CHARS);
    assertThat(value).endsWith("...");
    assertThat(value).startsWith("aaa");
  }

  @Test
  void surrogatePair_isNeverSplitByTruncation() {
    // Pad so the emoji's HIGH surrogate lands exactly on the last kept character of the cut.
    int cut = MdcProjector.MAX_VALUE_CHARS - "...".length();
    String value = repeat('a', cut - 1) + "\ud83d\ude00" + repeat('b', 500); // U+1F600
    Map<String, String> mdc = Map.of("emoji", value);

    String projected = MdcProjector.project(List.of("emoji"), lookup(mdc)).get("emoji");

    // Backed off by one char rather than keeping a lone high surrogate, which would re-encode to
    // U+FFFD and corrupt exactly the emoji/CJK-supplementary content it was meant to preserve.
    assertThat(projected).hasSize(MdcProjector.MAX_VALUE_CHARS - 1).endsWith("...");
    assertThat(new String(projected.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
        .isEqualTo(projected)
        .doesNotContain("\ufffd");
  }

  // --- Caps -------------------------------------------------------------------------------------

  @Test
  void moreKeysThanCap_isCappedAtMaxKeys() {
    Map<String, String> mdc = new LinkedHashMap<>();
    List<String> allowed = new ArrayList<>();
    for (int i = 0; i < MdcProjector.MAX_KEYS + 10; i++) {
      String key = "k" + i;
      mdc.put(key, "v");
      allowed.add(key);
    }

    Map<String, String> projected = MdcProjector.project(allowed, lookup(mdc));

    assertThat(projected).hasSize(MdcProjector.MAX_KEYS);
    assertThat(projected).containsKey("k0").doesNotContainKey("k" + MdcProjector.MAX_KEYS);
  }

  @Test
  void totalCharBudget_stopsAddingFurtherEntries() {
    Map<String, String> mdc = new LinkedHashMap<>();
    List<String> allowed = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String key = "k" + i; // 2 chars + 200 chars of value = 202 per entry
      mdc.put(key, repeat('x', 200));
      allowed.add(key);
    }

    Map<String, String> projected = MdcProjector.project(allowed, lookup(mdc));

    // 5 * 202 = 1010 fits; a 6th entry would reach 1212 > MAX_TOTAL_CHARS, so iteration stops there
    // — under the key cap, but well before the context block could eat the 4096-byte body budget.
    assertThat(projected).hasSize(5);
    int totalChars =
        projected.entrySet().stream()
            .mapToInt(e -> e.getKey().length() + e.getValue().length())
            .sum();
    assertThat(totalChars).isLessThanOrEqualTo(MdcProjector.MAX_TOTAL_CHARS);
  }

  // --- Keys are untrusted too --------------------------------------------------------------------

  @Test
  void keyItself_isScrubbedAndTruncated() {
    // An allow-list entry can come from an environment variable, so the KEY is no cleaner than the
    // value: it is scrubbed and truncated the same way, while the lookup still uses the RAW key.
    String hostileKey = "ten\nant";
    String longKey = repeat('k', 300);
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put(hostileKey, "acme");
    mdc.put(longKey, "v");

    Map<String, String> projected =
        MdcProjector.project(List.of(hostileKey, longKey), lookup(mdc));

    assertThat(projected).containsEntry("ten ant", "acme");
    assertThat(projected.keySet())
        .filteredOn(k -> k.startsWith("kkk"))
        .allSatisfy(k -> assertThat(k).hasSize(MdcProjector.MAX_VALUE_CHARS).endsWith("..."));
  }

  // --- sanitize() -------------------------------------------------------------------------------

  @Test
  void sanitize_isIdempotent() {
    Map<String, String> hostile = new LinkedHashMap<>();
    hostile.put("clean", "value");
    hostile.put("news\nline", "a\nb");
    hostile.put("long", repeat('z', 900));
    for (int i = 0; i < 40; i++) {
      hostile.put("extra" + i, repeat('q', 100));
    }

    Map<String, String> once = MdcProjector.sanitize(hostile);
    Map<String, String> twice = MdcProjector.sanitize(once);

    // Equal content AND equal order: AlertEvent re-runs sanitize on every construction, so a second
    // pass over an already-projected map must be a no-op rather than a slow erosion of the values.
    assertThat(twice).isEqualTo(once);
    assertThat(new ArrayList<>(twice.keySet())).isEqualTo(new ArrayList<>(once.keySet()));
    assertThat(once).hasSizeLessThanOrEqualTo(MdcProjector.MAX_KEYS);
    assertThat(MdcProjector.sanitize(null)).isEmpty();
  }
}
