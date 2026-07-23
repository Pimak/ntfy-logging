package io.github.pimak.ntfy.core;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Single centralized point for every visible string this module produces (body labels and
 * self-diagnostic status messages), now backed by a per-locale {@link ResourceBundle} catalog so the
 * same text can be produced in any shipped language. Keeping all literal text here — rather than
 * scattered across the engine/publisher — is what makes the translation layer possible without
 * touching the public API.
 *
 * <p>An instance is bound to one locale via {@link #forLocale(Locale)} and read through no-arg
 * accessors (fixed strings) and composer methods (dynamic strings). The English bundle
 * ({@code AlertMessages.properties}) is both the source of truth and the wholesale fallback; a whole
 * missing locale, or an individual key missing from a translation, resolves to English.
 *
 * <p><strong>Credential safety is structural.</strong> Every fixed status/warning string is an
 * argument-less bundle key with no {@code MessageFormat} placeholder, so no translation — however
 * hostile — can interpolate a token, username, or password (zero values are ever passed). The
 * composer methods keep their sanitizing logic in Java (e.g. the userinfo-stripping regex on the URL
 * runs BEFORE the scrubbed value is handed to {@link MessageFormat}); {@code MessageFormat}
 * substitutes arguments literally without re-parsing them, so a translation may move the
 * already-safe {@code {0}}/{@code {1}} placeholders around in the text but must keep exactly that
 * placeholder set — never adding or removing one ({@code AlertMessagesBundleSafetyTest} enforces the
 * exact argument count per pattern key in every shipped bundle). Every numeric argument is
 * passed pre-formatted via {@link String#valueOf(int)} so {@code MessageFormat} never applies
 * locale-specific digit grouping — English output stays byte-for-byte identical to the hand-built
 * strings this class replaced.
 */
final class AlertMessages {

  /** Fully-qualified base name of the {@code .properties} bundle (co-located with this class). */
  private static final String BUNDLE_BASE_NAME = "io.github.pimak.ntfy.core.AlertMessages";

  /** Number of top loggers listed individually in a digest body before an overflow marker. */
  private static final int DIGEST_TOP_LOGGER_LIMIT = 5;

  /** Fallback window when both the configured and the default window are {@code null}. */
  private static final Duration DEFAULT_SUPPRESSION_WINDOW = Duration.ofMillis(180_000L);

  private final ResourceBundle bundle;

  private AlertMessages(ResourceBundle bundle) {
    this.bundle = bundle;
  }

  /**
   * Loads the message catalog for {@code locale}. The candidate chain is deliberately exactly
   * requested-locale (e.g. {@code de_DE} &rarr; {@code de}) &rarr; base bundle (English), with no
   * host-JVM-default-locale intermediate: {@link ResourceBundle.Control#getNoFallbackControl} makes
   * notification language deterministic and never dependent on wherever the process happens to run.
   * A {@code null} locale, or any locale with no shipped translation, yields the English base.
   */
  static AlertMessages forLocale(Locale locale) {
    Locale target = locale == null ? Locale.ENGLISH : locale;
    ResourceBundle bundle =
        ResourceBundle.getBundle(
            BUNDLE_BASE_NAME,
            target,
            AlertMessages.class.getClassLoader(),
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    return new AlertMessages(bundle);
  }

  /**
   * Returns the raw string for {@code key}. An individual key absent from the requested translation
   * resolves through the bundle's parent chain to the English base automatically; the defensive
   * {@code catch} only fires in the impossible case that a key is absent even from the base bundle,
   * where returning the key itself is far better than throwing out of a diagnostics call.
   */
  private String get(String key) {
    try {
      return bundle.getString(key);
    } catch (MissingResourceException e) {
      return key;
    }
  }

  /**
   * Formats a {@code MessageFormat} pattern key with {@code args}. Uses {@link Locale#ROOT} on the
   * formatter because every numeric argument is already pre-formatted to a string by the caller, so
   * no locale number-grouping is applied and English output is byte-identical to the old strings.
   */
  private String fmt(String key, Object... args) {
    return new MessageFormat(get(key), Locale.ROOT).format(args);
  }

  // --- Body labels (fixed text, zero arguments) ---

  String labelMessage() {
    return get("label.message");
  }

  String labelLogger() {
    return get("label.logger");
  }

  String labelCause() {
    return get("label.cause");
  }

  String labelTimestamp() {
    return get("label.timestamp");
  }

  // --- Fixed, credential-safe status/warning strings (fixed text, zero arguments) ---

  String statusDisabledUnconfigured() {
    return get("status.disabledUnconfigured");
  }

  String statusDisabledPartialConfig() {
    return get("status.disabledPartialConfig");
  }

  String statusTokenAndBasicBothSet() {
    return get("status.tokenAndBasicBothSet");
  }

  /**
   * Fixed warning emitted from {@code start()} when exactly one of username/password is configured
   * (and no token supersedes the pair) — {@code AuthMode.fromCredentials} ignores the incomplete
   * pair and falls back to {@code None}, so every publish would silently go out unauthenticated.
   * Credential-safe: fixed text only, never interpolating the configured username or password.
   */
  String statusIncompleteBasicAuth() {
    return get("status.incompleteBasicAuth");
  }

  /**
   * Fixed warning emitted from {@code start()} when credentials would traverse a plain {@code
   * http://} URL — a configured token, a username/password pair, or userinfo embedded in the URL
   * itself ({@code http://user:pass@host}). The Authorization header (or URL userinfo) and every
   * alert body would travel in cleartext, readable by any on-path observer. Credential-safe: fixed
   * text only, never interpolating the credential or the URL.
   */
  String statusCredentialsOverPlainHttp() {
    return get("status.credentialsOverPlainHttp");
  }

  /**
   * Fixed warning emitted from {@code start()} when {@code requireHttpsForCredentials} is enabled
   * and credentials would traverse a plain {@code http://} URL — the engine refuses activation
   * instead of transmitting a secret in cleartext. Credential-safe: fixed text only, never
   * interpolating the credential or the URL.
   */
  String statusCredentialsOverPlainHttpRefused() {
    return get("status.credentialsOverPlainHttpRefused");
  }

  /**
   * Fixed message emitted from {@code start()} when the configured topic is not a valid ntfy topic
   * name — the engine refuses activation rather than build request paths from it. Deliberately does
   * NOT interpolate the rejected value (it could contain control characters).
   */
  String statusInvalidTopic() {
    return get("status.invalidTopic");
  }

  /**
   * Fixed message emitted from {@code start()} when the configured URL is not a structurally valid
   * http(s) endpoint (no scheme, a non-http(s) scheme, unparseable syntax, or no authority) — the
   * engine refuses activation rather than manufacture a per-event publish failure for every alert.
   * Deliberately does NOT interpolate the rejected value (it could carry a credential in a
   * {@code user:pass@host} URL).
   */
  String statusInvalidUrl() {
    return get("status.invalidUrl");
  }

  /**
   * Fixed warning emitted from {@code start()} when a configured priority/tags value contains
   * non-printable-ASCII characters — the publisher omits such a header instead of letting it abort
   * every publish at the header-build boundary.
   */
  String statusInvalidPriorityOrTags() {
    return get("status.invalidPriorityOrTags");
  }

  /**
   * Fixed message emitted from {@code start()} when {@code suppressionWindow} is unset, zero, or
   * negative — the engine falls back to the default window. Credential-safe: fixed text only.
   */
  String statusInvalidSuppressionWindow() {
    return get("status.invalidSuppressionWindow");
  }

  /**
   * Fixed message emitted from {@code start()} when {@code connectTimeout} is unset, zero, or
   * negative — the engine falls back to the default connect timeout. Credential-safe: fixed text
   * only, never interpolating the offending value.
   */
  String statusInvalidConnectTimeout() {
    return get("status.invalidConnectTimeout");
  }

  /**
   * Fixed message emitted from {@code start()} when {@code requestTimeout} is unset, zero, or
   * negative — the engine falls back to the default request timeout. Credential-safe: fixed text
   * only, never interpolating the offending value.
   */
  String statusInvalidRequestTimeout() {
    return get("status.invalidRequestTimeout");
  }

  /**
   * Fixed message emitted from {@code start()} when {@code asyncQueueCapacity} is zero or negative
   * while async delivery is enabled — the engine clamps it to a safe minimum. Credential-safe: fixed
   * text only.
   */
  String statusInvalidAsyncQueueCapacity() {
    return get("status.invalidAsyncQueueCapacity");
  }

  /**
   * Fixed, throttled warning emitted by the async delivery rejection handler when the bounded queue
   * is full and an error alert is dropped. Credential-safe: fixed text only.
   */
  String statusAsyncQueueOverflow() {
    return get("status.asyncQueueOverflow");
  }

  /**
   * Fixed, generic message for an unexpected {@code RuntimeException} inside {@code submit()}.
   * Deliberately never concatenates {@code e.getMessage()} — the exception itself is still passed to
   * {@code error(String, Throwable)} for the diagnostics sink to record, but the *text* here never
   * embeds anything that could carry a credential.
   */
  String publishUnexpectedError() {
    return get("publish.unexpectedError");
  }

  // --- Composers (dynamic text; sanitizing logic stays in Java, never in a translation) ---

  /**
   * Composes the "engine is active" status line. Never includes the token or any other credential —
   * only the topic and the url with any userinfo stripped: a {@code https://user:pass@host} URL (a
   * form ntfy documents for basic auth) must never echo its password into diagnostic output. The
   * scrub runs here, in Java, BEFORE the value reaches {@link MessageFormat}.
   */
  String statusActive(String url, String topic) {
    // Greedy [^/?#]* strips up to the LAST '@' inside the authority (bounded by the first '/', '?',
    // or '#'): a raw unencoded '@' inside the password ("//user:p@ss@host") must not leave a
    // password tail, while a later '@' in a query/fragment ("//host?email=a@b.com") must not be
    // mistaken for userinfo and over-strip the authority to "//b.com". Terminating on '?'/'#' as
    // well as '/' matches the authority rule in AlertEngine.urlHasUserinfo.
    String safeUrl = url == null ? null : url.replaceFirst("//[^/?#]*@", "//");
    return fmt("status.active", safeUrl, topic);
  }

  /**
   * Composes a publish-failure status line. Only the topic and (when present) the HTTP status code
   * are included — never the token, username/password, full authenticated URL, or a raw exception
   * message that could embed a credential. The status code is pre-stringified so no locale
   * number-grouping is ever applied.
   */
  String publishFailed(String topic, Integer httpStatus) {
    if (httpStatus != null) {
      return fmt("publish.failed.withStatus", topic, String.valueOf(httpStatus));
    }
    return fmt("publish.failed.noStatus", topic);
  }

  /**
   * Composes the "excluded loggers" status line emitted once from {@code start()}. Credential-safe —
   * only interpolates operator-configured logger-name prefixes, never a token/username/password. The
   * {@code String.join} happens in Java; the empty case takes a fixed, argument-less key.
   */
  String statusExclusions(List<String> excludedPrefixes) {
    if (excludedPrefixes == null || excludedPrefixes.isEmpty()) {
      return get("status.exclusions.none");
    }
    return fmt("status.exclusions.list", String.join(", ", excludedPrefixes));
  }

  /**
   * Composes the storm-digest title. {@code titleOrAppName} may be {@code null} (treated as empty
   * string, no NPE). Credential-safe — only interpolates a caller-supplied label and an integer
   * count (pre-stringified so no digit-grouping is applied).
   */
  String digestTitle(String titleOrAppName, int count) {
    String base = titleOrAppName == null ? "" : titleOrAppName;
    return fmt("digest.title", base, String.valueOf(count));
  }

  /**
   * Composes the storm-digest body: a summary line followed by up to {@value
   * #DIGEST_TOP_LOGGER_LIMIT} "&lt;count&gt;x &lt;loggerName&gt;" lines (sorted by count descending),
   * plus a "+N others" line when more than {@value #DIGEST_TOP_LOGGER_LIMIT} distinct loggers were
   * tallied. All sorting/limiting/newline assembly stays in Java; the bundle supplies only the
   * per-line phrasing. Does NOT apply {@link PayloadTruncator} — truncation happens at the publish
   * site. Credential-safe — only interpolates count, logger names, and a window description, never
   * token/username/password.
   */
  String digestBody(
      int count, Map<String, Integer> perLoggerTally, String windowDescription) {
    StringBuilder sb = new StringBuilder();
    sb.append(fmt("digest.body.summary", String.valueOf(count), windowDescription));

    List<Map.Entry<String, Integer>> sorted =
        perLoggerTally.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .toList();

    int shown = Math.min(DIGEST_TOP_LOGGER_LIMIT, sorted.size());
    for (int i = 0; i < shown; i++) {
      Map.Entry<String, Integer> entry = sorted.get(i);
      sb.append('\n')
          .append(fmt("digest.body.loggerLine", String.valueOf(entry.getValue()), entry.getKey()));
    }

    int remaining = sorted.size() - shown;
    if (remaining > 0) {
      sb.append('\n').append(fmt("digest.body.overflow", String.valueOf(remaining)));
    }
    return sb.toString();
  }

  /**
   * Human-readable window description for the digest body (e.g. {@code "3 minutes"}). Moved here from
   * the engine so its plural phrasing is translated alongside the rest of the digest instead of
   * leaking untranslated English. The count is pre-stringified so no digit-grouping is applied.
   *
   * <p>A {@code null}, zero, or negative {@code duration} describes the fallback window instead:
   * {@code start()} clamps exactly those values to the default window for the actual digest
   * schedule, so describing the raw invalid value would make the digest claim a window (e.g.
   * {@code "1 second"}) the engine never ran at.
   */
  String describeWindow(Duration duration, Duration defaultWindow) {
    Duration fallback = defaultWindow == null ? DEFAULT_SUPPRESSION_WINDOW : defaultWindow;
    long ms =
        duration == null || duration.isZero() || duration.isNegative()
            ? fallback.toMillis()
            : duration.toMillis();
    long minutes = ms / 60_000L;
    if (minutes >= 1) {
      return fmt(minutes == 1 ? "window.minute" : "window.minutes", String.valueOf(minutes));
    }
    long seconds = Math.max(ms / 1_000L, 1L);
    return fmt(seconds == 1 ? "window.second" : "window.seconds", String.valueOf(seconds));
  }
}
