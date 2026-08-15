package io.github.pimak.ntfy.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Projects a logging framework's diagnostic context (MDC) onto the small, bounded, sanitized map of
 * {@code key -> value} pairs an alert body may carry — the {@code include-mdc-keys} feature.
 *
 * <p><strong>The allow-list is the whole point.</strong> Only keys the operator named are ever read,
 * there is deliberately no wildcard and no "include everything" mode, and an unset allow-list yields
 * an empty map without the MDC ever being consulted. The MDC of a production application routinely
 * holds session tokens, user identifiers, and other data that must never leave the process in a
 * notification, so the selection can only ever widen by an explicit configuration change.
 *
 * <p>Adapters (logback, JUL, ...) call {@link #project} with the configured key list and a lookup
 * function bound to their framework's context, and hand the result to {@link AlertEvent}. This class
 * is public precisely because those adapters live in sibling modules; it is otherwise an internal
 * utility with no state.
 *
 * <p>Three properties make the projection safe to append to an alert body:
 *
 * <ul>
 *   <li><strong>Structure-safe.</strong> Every control character, Unicode line/paragraph separator
 *       and format (Cf) character is replaced with a single space, so an MDC value can neither forge
 *       body structure (a fake {@code Caused by:} line) nor break {@link PayloadTruncator}'s
 *       line-by-line splitting, nor smuggle a bidi override past a reader's eyes.
 *   <li><strong>Bounded.</strong> At most {@value #MAX_KEYS} entries, each value at most {@value
 *       #MAX_VALUE_CHARS} characters, and at most {@value #MAX_TOTAL_CHARS} characters overall — so
 *       the context block can never crowd the message, cause chain, and stack frames out of ntfy's
 *       4096-byte body budget.
 *   <li><strong>Cheap.</strong> The caps are enforced <em>while</em> iterating, so a pathological
 *       10 000-entry allow-list costs at most {@value #MAX_KEYS} context lookups per event, not
 *       10 000.
 * </ul>
 *
 * <p>Every guard here is idempotent: {@link #sanitize} applied to an already-sanitized map returns an
 * equal map, which is what lets {@link AlertEvent} re-run it on construction as belt-and-braces
 * without mangling a projection this class already produced.
 */
public final class MdcProjector {

  /** Maximum number of MDC entries retained; extra allow-list entries are dropped. */
  static final int MAX_KEYS = 16;

  /** Maximum characters of a single value, INCLUDING the {@value #ELLIPSIS} truncation marker. */
  static final int MAX_VALUE_CHARS = 256;

  /** Maximum total characters (keys + values) retained across all entries. */
  static final int MAX_TOTAL_CHARS = 1024;

  /**
   * ASCII, never the single-character '…': every body label in the shipped bundles is ASCII, and
   * {@link PayloadTruncator} budgets in UTF-8 <em>bytes</em>, so an ASCII marker costs exactly the
   * three bytes it looks like it costs.
   */
  private static final String ELLIPSIS = "...";

  private MdcProjector() {}

  /**
   * Reads the allow-listed keys out of a logging context and returns the sanitized, bounded,
   * <em>configured-order</em> map to render into an alert body.
   *
   * <p>Semantics, in order:
   *
   * <ol>
   *   <li>A {@code null}/empty {@code allowedKeys} or a {@code null} {@code lookup} returns an empty
   *       map <em>without calling {@code lookup} even once</em> — the feature being unset must cost
   *       nothing and must not touch the context at all.
   *   <li>{@code allowedKeys} is walked in order; that order IS the rendering order in the body.
   *   <li>{@code null}/blank keys and keys already consulted are skipped.
   *   <li>A key whose value is absent, blank, or nothing but control characters produces no entry —
   *       and therefore no line in the body — rather than an empty {@code "key: "} line.
   *   <li>A {@code lookup} that throws is treated exactly like an absent value: a hostile or simply
   *       broken context implementation must never break the logging path it is decorating.
   * </ol>
   *
   * @param allowedKeys the operator-configured MDC key names, in the order they should be rendered
   * @param lookup reads one key from the ambient logging context, returning {@code null} when absent
   * @return an unmodifiable, insertion-ordered map; never {@code null}
   */
  public static Map<String, String> project(
      List<String> allowedKeys, Function<String, String> lookup) {
    if (allowedKeys == null || allowedKeys.isEmpty() || lookup == null) {
      // Feature unset (or no context available): the fast path must not consult the MDC at all.
      return Map.of();
    }

    Map<String, String> projected = new LinkedHashMap<>();
    // Raw (pre-sanitization) keys already consulted. Checked BEFORE the lookup so a duplicated
    // allow-list entry costs zero extra context reads, not just zero extra body lines.
    Set<String> consulted = new HashSet<>();
    int totalChars = 0;

    for (String key : allowedKeys) {
      if (projected.size() >= MAX_KEYS) {
        // Cap reached: stop iterating rather than continue looking keys up and discarding them.
        break;
      }
      if (key == null || key.isBlank()) {
        continue;
      }
      if (!consulted.add(key)) {
        continue;
      }

      String raw;
      try {
        raw = lookup.apply(key);
      } catch (RuntimeException e) {
        // An MDC implementation that throws must degrade to "key absent", never propagate into the
        // caller's logging call. Nothing is diagnosed here: this runs per event on the logging path.
        raw = null;
      }

      // Look up with the RAW key (that is what the context is keyed by) but store under the
      // sanitized one: the key itself may come from an environment variable and is not trusted.
      String cleanKey = sanitizeText(key);
      String cleanValue = sanitizeText(raw);
      if (cleanKey.isBlank() || cleanValue.isBlank()) {
        continue;
      }
      // Two distinct raw keys can collapse onto one sanitized key; first one wins, as everywhere.
      if (projected.containsKey(cleanKey)) {
        continue;
      }

      int cost = cleanKey.length() + cleanValue.length();
      if (totalChars + cost > MAX_TOTAL_CHARS) {
        break;
      }
      totalChars += cost;
      projected.put(cleanKey, cleanValue);
    }

    // unmodifiableMap over the LinkedHashMap just built — deliberately NOT Map.copyOf, which does
    // not preserve iteration order, and configured order is part of this method's contract.
    return Collections.unmodifiableMap(projected);
  }

  /**
   * Applies the same per-entry scrubbing, truncation, and {@value #MAX_KEYS}/{@value
   * #MAX_TOTAL_CHARS} caps to an <em>already-built</em> map, preserving its encounter order. This is
   * the belt-and-braces path {@link AlertEvent} runs on every construction, so even an event
   * hand-built by a third-party adapter that passed a raw MDC map straight through is still capped,
   * scrubbed, and truncated — only the allow-list <em>selection</em> is the adapter's job.
   *
   * <p>Idempotent by construction: scrubbing has no fixed point to chase (a replaced character
   * becomes a plain space) and truncation lands on exactly {@value #MAX_VALUE_CHARS} characters, so
   * a second pass finds nothing left to do.
   *
   * @param raw the map to sanitize; {@code null} yields an empty map
   * @return an unmodifiable, encounter-ordered map; never {@code null}
   */
  static Map<String, String> sanitize(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }

    Map<String, String> clean = new LinkedHashMap<>();
    int totalChars = 0;
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      if (clean.size() >= MAX_KEYS) {
        break;
      }
      String cleanKey = sanitizeText(entry.getKey());
      String cleanValue = sanitizeText(entry.getValue());
      if (cleanKey.isBlank() || cleanValue.isBlank()) {
        continue;
      }
      if (clean.containsKey(cleanKey)) {
        continue;
      }
      int cost = cleanKey.length() + cleanValue.length();
      if (totalChars + cost > MAX_TOTAL_CHARS) {
        break;
      }
      totalChars += cost;
      clean.put(cleanKey, cleanValue);
    }
    // Same rationale as project(): unmodifiable wrapper over an order-preserving LinkedHashMap.
    return Collections.unmodifiableMap(clean);
  }

  /**
   * Scrubs, strips, then truncates one key or value. The order matters: stripping BEFORE truncating
   * is what keeps an over-long value at exactly {@value #MAX_VALUE_CHARS} characters (a strip
   * afterwards could shorten it again) and therefore keeps the whole operation idempotent.
   */
  private static String sanitizeText(String text) {
    if (text == null) {
      return "";
    }
    return truncate(scrub(text));
  }

  /**
   * Single pass replacing every structure-breaking or invisible character with one plain space, then
   * stripping. Replaced (never deleted, so words never silently fuse together):
   *
   * <ul>
   *   <li>{@link Character#isISOControl} — {@code \n}, {@code \r}, {@code \t}, NUL and the ANSI
   *       escape, i.e. everything that could forge a body line or drive a terminal;
   *   <li>{@code U+2028} LINE SEPARATOR and {@code U+2029} PARAGRAPH SEPARATOR, which are not ISO
   *       controls but are line breaks to plenty of renderers;
   *   <li>{@link Character#FORMAT} (Cf) — bidi overrides (RLO/LRO) that can visually reverse the
   *       text a human reads, and zero-width joiners that hide content outright.
   * </ul>
   *
   * <p>Surrogates are left untouched (their general category is {@code Cs}, not {@code Cf}), so
   * supplementary characters survive intact.
   */
  private static String scrub(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      sb.append(isNeutralized(c) ? ' ' : c);
    }
    return sb.toString().strip();
  }

  /** See {@link #scrub}: the three classes of character that never reach an alert body verbatim. */
  private static boolean isNeutralized(char c) {
    // U+2028/U+2029 are written as escapes on purpose: pasted literally they are invisible in
    // an editor and indistinguishable from the plain space they are replaced with.
    return Character.isISOControl(c)
        || c == '\u2028'
        || c == '\u2029'
        || Character.getType(c) == Character.FORMAT;
  }

  /**
   * Truncates to exactly {@value #MAX_VALUE_CHARS} characters including the {@value #ELLIPSIS}
   * marker. The exactness is deliberate: a second pass sees {@code 256 > 256 == false} and leaves the
   * value alone, which is what makes {@link #sanitize} idempotent.
   *
   * <p>When the cut would land between the two halves of a surrogate pair it is backed off by one
   * character (yielding 255) — a split pair re-encodes to {@code U+FFFD} and would corrupt exactly
   * the emoji/CJK-supplementary values a correlation id might carry.
   */
  private static String truncate(String text) {
    if (text.length() <= MAX_VALUE_CHARS) {
      return text;
    }
    int cut = MAX_VALUE_CHARS - ELLIPSIS.length();
    if (Character.isHighSurrogate(text.charAt(cut - 1))) {
      // charAt(cut - 1) is the LAST character kept; its low-surrogate partner at charAt(cut) is
      // about to be dropped, so drop the pair entirely instead of half of it.
      cut--;
    }
    return text.substring(0, cut) + ELLIPSIS;
  }
}
