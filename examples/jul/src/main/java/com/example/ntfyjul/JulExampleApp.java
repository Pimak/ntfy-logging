package com.example.ntfyjul;

import io.github.pimak.ntfy.jul.NtfyJulHandler;
import io.github.pimak.ntfy.jul.NtfyJulInstaller;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Example of a plain {@code java.util.logging} application with ntfy error alerting: one {@link
 * NtfyJulInstaller#install()} line early in {@code main} attaches the ntfy handler to the root
 * logger from the ambient configuration ({@code NTFY_URL}/{@code NTFY_TOPIC} environment variables,
 * {@code -Dntfy.*} system properties, or a trusted classpath {@code ntfy.properties}) — and does
 * nothing when ntfy is not configured, so the line is always safe to keep.
 *
 * <p>Every SEVERE log then arrives as a push notification. On shutdown, {@link
 * NtfyJulInstaller#uninstall} stops the handler's engine cleanly: queued async alerts fold into the
 * storm digest, which is flushed before the HTTP client is released. This module's integration
 * tests drive the same install path (via {@code -Dntfy.*} properties) against a loopback ntfy
 * stand-in.
 */
public final class JulExampleApp {

  private static final Logger log = Logger.getLogger(JulExampleApp.class.getName());

  private JulExampleApp() {}

  public static void main(String[] args) {
    // Zero-code-style install from the environment; empty when NTFY_URL/NTFY_TOPIC are not set.
    Optional<NtfyJulHandler> ntfy = NtfyJulInstaller.install();
    if (ntfy.isEmpty()) {
      System.err.println("ntfy not configured — set NTFY_URL and NTFY_TOPIC to see the alert");
    }

    log.info("nightly batch starting");
    try {
      processNightlyBatch();
      log.info("nightly batch finished");
    } catch (IllegalStateException e) {
      // An ordinary SEVERE log — the installed handler turns it into a push notification.
      log.log(Level.SEVERE, "Nightly batch failed", e);
    }

    // Clean shutdown: detach and stop the handler (JUL's own LogManager shutdown hook would also
    // close attached handlers on normal JVM exit).
    ntfy.ifPresent(handler -> NtfyJulInstaller.uninstall(Logger.getLogger(""), handler));
  }

  private static void processNightlyBatch() {
    throw new IllegalStateException("row 42: malformed record");
  }
}
