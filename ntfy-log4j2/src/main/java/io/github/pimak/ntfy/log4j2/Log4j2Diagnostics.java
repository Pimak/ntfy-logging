package io.github.pimak.ntfy.log4j2;

import org.apache.logging.log4j.status.StatusLogger;

import io.github.pimak.ntfy.core.Diagnostics;

/**
 * Routes the engine's {@link Diagnostics} callbacks onto log4j2's {@link StatusLogger} — the
 * framework's own internal-diagnostics channel and the exact analogue of Logback's StatusManager.
 *
 * <p>Deliberately never uses {@code LogManager.getLogger(...)}: an engine self-diagnostic emitted
 * through the application logging pipeline would be handed straight back to the appender that
 * produced it, and an ERROR-level line would then alert about the alerting. {@code StatusLogger}
 * sits outside the {@code Configuration} entirely, so the feedback loop cannot close.
 *
 * <p>The core engine already guarantees these messages are generic and never carry a token,
 * password or embedded userinfo; this sink only relays them, so no credential can reach a status
 * line through it either.
 */
final class Log4j2Diagnostics implements Diagnostics {

  // The StatusLogger is a process-wide singleton; caching the handle avoids a lookup per message
  // on the (rare) diagnostic path without pinning anything that could leak.
  private final StatusLogger statusLogger = StatusLogger.getLogger();

  @Override
  public void info(String message) {
    statusLogger.info(message);
  }

  @Override
  public void warn(String message) {
    statusLogger.warn(message);
  }

  @Override
  public void error(String message, Throwable throwable) {
    statusLogger.error(message, throwable);
  }
}
