package com.example.ntfylog4j2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Example of a plain Log4j2 application with ntfy error alerting: nothing here mentions ntfy — the
 * integration is wired entirely in {@code log4j2.xml} ({@code src/main/resources}), where the
 * {@code <Ntfy>} element publishes every ERROR-level event as a push notification.
 *
 * <p>Run it with {@code NTFY_URL} and {@code NTFY_TOPIC} set (the XML substitutes them, and
 * {@code -Dntfy.url} / {@code -Dntfy.topic} override them) and the simulated batch failure below
 * arrives as a notification. This module's integration tests run the same {@code log4j2.xml}
 * against a loopback ntfy stand-in.
 */
public final class Log4j2ExampleApp {

  private static final Logger log = LogManager.getLogger(Log4j2ExampleApp.class);

  private Log4j2ExampleApp() {}

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
