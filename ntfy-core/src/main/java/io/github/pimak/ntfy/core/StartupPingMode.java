package io.github.pimak.ntfy.core;

import java.util.Locale;

/**
 * What the opt-in startup self-test does when {@code AlertEngine.start()} finishes activating.
 *
 * <p>An ntfy alerting backend is silent by design — it only speaks when something goes wrong — so a
 * revoked token, a renamed topic or a blocked egress route all look identical at boot: nothing
 * happens. {@code start()} validates only what it can check locally (URL syntax, topic charset,
 * auth-pair completeness, timeouts) and never touches the network, so it cannot tell "correctly
 * configured" apart from "token revoked last week". This flag opts into an actual round-trip that
 * settles the question at deploy time instead of during the first real incident.
 *
 * <p>{@link #OFF} is the default and is a true no-op: no request, and no diagnostic line either, so
 * the default path's diagnostic stream stays byte-identical to a build without this feature.
 */
public enum StartupPingMode {

  /** No self-test. Default; emits no request and no diagnostic. */
  OFF,

  /**
   * Read-only reachability check — {@code GET {url}/{topic}/json?poll=1&since=none}. Validates DNS,
   * TLS, that the endpoint really is an ntfy server, that the topic is addressable, and that the
   * credentials are accepted for <em>read</em>. Publishes nothing, so subscribers see no noise.
   *
   * <p><strong>Known blind spot:</strong> ntfy ACLs separate read from write, so a read-only token
   * (or one revoked for writes only) passes this check and still fails every real alert. On an open
   * server, where reads are anonymous, a probe proves nothing at all about credentials. {@code
   * PROBE} answers "can I reach this server?"; only {@link #PUBLISH} answers "will my alerts
   * actually be delivered?".
   */
  PROBE,

  /**
   * Posts a real low-priority test notification through the production publish path, so the exact
   * code, headers and credentials that carry real alerts are exercised. The cost is one
   * notification per boot for every subscriber of the topic.
   */
  PUBLISH;

  /**
   * Parses a configured value case-insensitively, returning {@code null} — never throwing — when
   * the value matches no constant.
   *
   * <p>{@code null} rather than a silent fallback to {@link #OFF} on purpose: {@code ConfigLoader}
   * and the adapters keep the default for a malformed value (matching how a bad int or duration is
   * handled) but must still be able to tell "unset" from "misspelled" so a typo like {@code
   * startup-ping=prob} is warned about instead of masquerading as a deliberate {@code off}.
   */
  public static StartupPingMode fromValue(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (StartupPingMode mode : values()) {
      if (mode.name().equals(normalized)) {
        return mode;
      }
    }
    return null;
  }
}
