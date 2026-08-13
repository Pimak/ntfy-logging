package io.github.pimak.ntfy.jul;

import io.github.pimak.ntfy.core.ConfigLoader;
import io.github.pimak.ntfy.core.Diagnostics;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.util.Optional;

/**
 * Resolves and vets the ambient ntfy configuration for the zero-code JUL entry points ({@link
 * NtfyJulAutoHandler} and {@link NtfyJulInstaller}). Config is loaded via {@link
 * ConfigLoader#load()} (system properties &gt; env &gt; classpath {@code ntfy.properties} &gt;
 * defaults) and then passed through the same supply-chain guard as the Logback zero-code
 * auto-install: an endpoint URL supplied <em>only</em> by a classpath {@code ntfy.properties} is
 * refused unless the operator opted in via {@code ntfy.allow-classpath-endpoint} / {@code
 * NTFY_ALLOW_CLASSPATH_ENDPOINT}, because any jar on the classpath can carry such a file.
 *
 * <p>Split from the entry points so the guard exists exactly once and is testable against a
 * hand-built {@link NtfyConfig} without touching real environment state.
 */
final class JulAmbientConfig {

  private JulAmbientConfig() {}

  /**
   * Loads the ambient config and vets it; empty when alerting should not install (unconfigured,
   * disabled, or classpath-endpoint refused).
   */
  static Optional<NtfyConfig> resolve(Diagnostics diagnostics) {
    return vet(ConfigLoader.load(), diagnostics);
  }

  /**
   * Vets an already-loaded config. An inactive config is silently empty (nothing to report — the
   * adapter is simply not configured); the classpath-endpoint refusal and its opted-in variant warn
   * loudly through {@code diagnostics} because they are security-relevant.
   */
  static Optional<NtfyConfig> vet(NtfyConfig cfg, Diagnostics diagnostics) {
    if (!cfg.isActive()) {
      return Optional.empty();
    }

    if (cfg.isEndpointFromClasspathFile()) {
      if (!cfg.isAllowClasspathEndpoint()) {
        // REFUSE, don't just warn: any jar on the classpath can carry a ntfy.properties, so a
        // classpath-only endpoint would let a compromised transitive dependency silently
        // exfiltrate error logs (messages + stack traces) to a server the operator never chose.
        // Same userinfo-strip as the engine's ACTIVE line: never echo embedded credentials.
        diagnostics.warn(
            "ntfy: endpoint URL comes ONLY from a classpath ntfy.properties (no NTFY_URL env var "
                + "or ntfy.url system property set) — refusing to auto-install alerting to '"
                + stripUserinfo(cfg.getUrl())
                + "' for supply-chain safety. If that file is yours and you trust it, opt in with "
                + "-Dntfy.allow-classpath-endpoint=true or NTFY_ALLOW_CLASSPATH_ENDPOINT=true, or "
                + "set the URL yourself via NTFY_URL / -Dntfy.url");
        return Optional.empty();
      }
      // Opted in: still WARN (not info) so the destination lands on stderr and an unexpected
      // endpoint is caught immediately.
      diagnostics.warn(
          "ntfy: endpoint URL comes from a classpath ntfy.properties (no NTFY_URL env var or "
              + "ntfy.url system property set) — alerts will be published to '"
              + stripUserinfo(cfg.getUrl())
              + "'; make sure that file is one you trust");
    }

    return Optional.of(cfg);
  }

  private static String stripUserinfo(String url) {
    return url.replaceFirst("//[^/?#]*@", "//");
  }
}
