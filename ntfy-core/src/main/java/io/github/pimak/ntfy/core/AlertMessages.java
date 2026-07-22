package io.github.pimak.ntfy.core;

/**
 * Single centralized point for every visible English string this module produces (body labels
 * and self-diagnostic status messages). Keeping all literal text here — rather than scattered
 * across the engine/publisher — is what makes a future locale/translation layer possible without
 * touching the public API.
 */
final class AlertMessages {

  private AlertMessages() {}

  static final String LABEL_MESSAGE = "Message: ";
  static final String LABEL_LOGGER = "Logger: ";
  static final String LABEL_CAUSE = "Caused by: ";
  static final String LABEL_TIMESTAMP = "Time: ";

  static final String STATUS_DISABLED_UNCONFIGURED =
      "ntfy alert engine not configured (url/topic unset) — inactive";

  static final String STATUS_DISABLED_PARTIAL_CONFIG =
      "url set but topic missing — engine disabled";

  static final String STATUS_TOKEN_AND_BASIC_BOTH_SET =
      "both token and username/password configured — token takes precedence";

  /**
   * Fixed warning emitted from {@code start()} when credentials are configured but the URL scheme
   * is plain {@code http://} — the Authorization header (token or basic credentials) and every
   * alert body would travel in cleartext, readable by any on-path observer.
   */
  static final String STATUS_CREDENTIALS_OVER_PLAIN_HTTP =
      "credentials configured with a plain http:// URL — the token/password and alert content "
          + "are sent unencrypted; use https://";

  /**
   * Fixed message emitted from {@code start()} when the configured topic is not a valid ntfy topic
   * name — the engine refuses activation rather than build request paths from it. Deliberately
   * does NOT interpolate the rejected value (it could contain control characters).
   */
  static final String STATUS_INVALID_TOPIC =
      "topic is not a valid ntfy topic name (allowed: A-Z, a-z, 0-9, '-', '_'; max 64 chars) "
          + "— engine disabled";

  /**
   * Fixed message emitted from {@code start()} when the configured URL is not a structurally valid
   * http(s) endpoint (no scheme, a non-http(s) scheme, unparseable syntax, or no authority) — the
   * engine refuses activation rather than manufacture a per-event publish failure for every alert.
   * Deliberately does NOT interpolate the rejected value (it could carry a credential in a
   * {@code user:pass@host} URL).
   */
  static final String STATUS_INVALID_URL =
      "url is not a valid http(s) endpoint (expected http:// or https:// with a host) "
          + "— engine disabled";

  /**
   * Fixed warning emitted from {@code start()} when a configured priority/tags value contains
   * non-printable-ASCII characters — the publisher omits such a header instead of letting it
   * abort every publish at the header-build boundary.
   */
  static final String STATUS_INVALID_PRIORITY_OR_TAGS =
      "configured priority/tags value contains non-printable-ASCII characters — the invalid "
          + "header will be omitted from publishes";

  /**
   * Fixed message emitted from {@code start()} when {@code suppressionWindow} is unset,
   * zero, or negative — the engine falls back to the default window instead of throwing out of
   * {@code start()} with resources half-acquired. Credential-safe: fixed text only.
   */
  static final String STATUS_INVALID_SUPPRESSION_WINDOW =
      "suppressionWindow must be positive — falling back to default (3 minutes)";

  /**
   * Fixed message emitted from {@code start()} when {@code connectTimeout} is unset, zero, or
   * negative — the engine falls back to the default connect timeout instead of throwing out of
   * {@code start()} with the HTTP thread pool already acquired. Credential-safe: fixed text only,
   * never interpolating the offending value.
   */
  static final String STATUS_INVALID_CONNECT_TIMEOUT =
      "connectTimeout must be positive — falling back to default (5 seconds)";

  /**
   * Fixed message emitted from {@code start()} when {@code requestTimeout} is unset, zero, or
   * negative — the engine falls back to the default request timeout instead of silently failing
   * every publish later. Credential-safe: fixed text only, never interpolating the offending value.
   */
  static final String STATUS_INVALID_REQUEST_TIMEOUT =
      "requestTimeout must be positive — falling back to default (10 seconds)";

  /**
   * Fixed message emitted from {@code start()} when {@code asyncQueueCapacity} is zero or negative
   * while async delivery is enabled — the engine clamps it to a safe minimum instead of building a
   * degenerate queue. Credential-safe: fixed text only.
   */
  static final String STATUS_INVALID_ASYNC_QUEUE_CAPACITY =
      "asyncQueueCapacity must be positive — falling back to the minimum (1)";

  /**
   * Fixed, throttled warning emitted by the async delivery rejection handler when the bounded queue
   * is full and an error alert is dropped. The dropped event is still folded into the suppression
   * count (so it surfaces in the next digest) — this warning only signals that the queue is
   * saturated. Credential-safe: fixed text only.
   */
  static final String STATUS_ASYNC_QUEUE_OVERFLOW =
      "ntfy async delivery queue is full — dropped alerts are folded into the storm digest";

  /**
   * Fixed, generic message for an unexpected {@code RuntimeException} inside {@code submit()}.
   * Deliberately never concatenates {@code e.getMessage()} — the exception itself
   * is still passed to {@code error(String, Throwable)} for the diagnostics sink to record, but the
   * *text* here never embeds anything that could carry a credential.
   */
  static final String PUBLISH_UNEXPECTED_ERROR = "ntfy publish threw unexpectedly";

  /**
   * Composes the "engine is active" status line. Never includes the token or any other credential
   * — only the topic and the url with any userinfo stripped: a {@code
   * https://user:pass@host} URL (a form ntfy documents for basic auth) must never echo its password
   * into diagnostic output.
   */
  static String statusActive(String url, String topic) {
    // Greedy [^/]* strips up to the LAST '@' before the first path slash: a raw unencoded '@'
    // inside the password ("//user:p@ss@host") must not leave a password tail in the output,
    // which the previous non-greedy-equivalent [^/@]+ form did.
    String safeUrl = url == null ? null : url.replaceFirst("//[^/]*@", "//");
    return "ntfy alert engine ACTIVE (url=" + safeUrl + ", topic=" + topic + ")";
  }

  /**
   * Composes a publish-failure status line. Only the topic and (when present) the HTTP status code
   * are included — never the token, username/password, full authenticated URL, or a raw exception
   * message that could embed a credential.
   */
  static String publishFailed(String topic, Integer httpStatus) {
    if (httpStatus != null) {
      return "ntfy publish failed for topic '" + topic + "' (HTTP " + httpStatus + ")";
    }
    return "ntfy publish failed for topic '" + topic + "'";
  }

  /**
   * Composes the "excluded loggers" status line emitted once from {@code start()}.
   * Credential-safe — only interpolates operator-configured logger-name prefixes, never a
   * token/username/password.
   */
  static String statusExclusions(java.util.List<String> excludedPrefixes) {
    if (excludedPrefixes == null || excludedPrefixes.isEmpty()) {
      return "ntfy alert engine: no excluded loggers configured";
    }
    return "ntfy alert engine excluded loggers: " + String.join(", ", excludedPrefixes);
  }

  /**
   * Number of top loggers listed individually in a digest body before an overflow marker.
   */
  private static final int DIGEST_TOP_LOGGER_LIMIT = 5;

  /**
   * Composes the storm-digest title: {@code "<titleOrAppName> — <count> errors suppressed"}
   * (em dash U+2014). {@code titleOrAppName} may be {@code null} (treated as empty string, no
   * NPE). Credential-safe — only interpolates a caller-supplied label and an integer
   * count.
   */
  static String digestTitle(String titleOrAppName, int count) {
    String base = titleOrAppName == null ? "" : titleOrAppName;
    return base + " — " + count + " errors suppressed";
  }

  /**
   * Composes the storm-digest body: a summary line followed by up to {@value
   * #DIGEST_TOP_LOGGER_LIMIT} "<count>x <loggerName>" lines (sorted by count descending), plus a
   * "+N others" line when more than {@value #DIGEST_TOP_LOGGER_LIMIT} distinct loggers were
   * tallied. Does NOT apply {@link PayloadTruncator} — truncation happens at the publish site.
   * Credential-safe — only interpolates count, logger names, and a window description,
   * never token/username/password.
   */
  static String digestBody(
      int count, java.util.Map<String, Integer> perLoggerTally, String windowDescription) {
    StringBuilder sb = new StringBuilder();
    sb.append(count).append(" errors suppressed in the last ").append(windowDescription);

    java.util.List<java.util.Map.Entry<String, Integer>> sorted =
        perLoggerTally.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
            .toList();

    int shown = Math.min(DIGEST_TOP_LOGGER_LIMIT, sorted.size());
    for (int i = 0; i < shown; i++) {
      java.util.Map.Entry<String, Integer> entry = sorted.get(i);
      sb.append('\n').append(entry.getValue()).append("x ").append(entry.getKey());
    }

    int remaining = sorted.size() - shown;
    if (remaining > 0) {
      sb.append('\n').append('+').append(remaining).append(" others");
    }
    return sb.toString();
  }
}
