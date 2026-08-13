package com.example.ntfylogback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example of a plain Logback application with ntfy error alerting: nothing here mentions ntfy — the
 * integration is wired entirely in {@code logback.xml} ({@code src/main/resources}), where the
 * {@code LogbackAlertAppender} publishes every ERROR-level event as a push notification.
 *
 * <p>Run it with {@code NTFY_URL} and {@code NTFY_TOPIC} set (the XML substitutes them) and the
 * simulated batch failure below arrives as a notification. This module's integration tests run the
 * same {@code logback.xml} against a loopback ntfy stand-in.
 */
public final class LogbackExampleApp {

  private static final Logger log = LoggerFactory.getLogger(LogbackExampleApp.class);

  private LogbackExampleApp() {}

  public static void main(String[] args) {
    log.info("nightly batch starting");
    try {
      processNightlyBatch();
      log.info("nightly batch finished");
    } catch (IllegalStateException e) {
      // An ordinary error log — the ntfy appender turns it into a push notification.
      log.error("Nightly batch failed", e);
    }
  }

  private static void processNightlyBatch() {
    throw new IllegalStateException("row 42: malformed record");
  }
}
