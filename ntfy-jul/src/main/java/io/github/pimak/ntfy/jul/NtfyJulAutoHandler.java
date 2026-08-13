package io.github.pimak.ntfy.jul;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Declarative zero-code entry point: name this class in a JUL {@code logging.properties} —
 *
 * <pre>{@code
 * handlers = java.util.logging.ConsoleHandler, io.github.pimak.ntfy.jul.NtfyJulAutoHandler
 * }</pre>
 *
 * — and {@code LogManager} instantiates it reflectively at logging init (the same mechanism
 * Tomcat's {@code conf/logging.properties} uses). Configuration comes from the ambient environment
 * via {@link JulAmbientConfig} ({@code -Dntfy.*} &gt; {@code NTFY_*} &gt; classpath {@code
 * ntfy.properties}), <em>not</em> from {@code LogManager} properties, so the keys are identical
 * across every adapter in the family.
 *
 * <p>Composes rather than extends {@link NtfyJulHandler}: when the environment does not configure
 * ntfy (or the classpath-endpoint guard refuses), the delegate is absent and this handler is a
 * permanent no-op — the constructor must never throw, because {@code LogManager} would report it
 * as a logging-configuration error on every startup.
 */
public final class NtfyJulAutoHandler extends Handler {

  /** Absent when the ambient config is inactive or was refused; the handler is then a no-op. */
  private final NtfyJulHandler delegate;

  /** Invoked reflectively by {@code LogManager}; also usable directly. Never throws. */
  public NtfyJulAutoHandler() {
    NtfyJulHandler built = null;
    try {
      built =
          JulAmbientConfig.resolve(new JulDiagnostics())
              .map(NtfyJulHandler::forConfig)
              .orElse(null);
    } catch (RuntimeException e) {
      // Defense in depth: a failure to read ambient config must degrade to a no-op handler, not
      // break the host application's logging initialization.
      new JulDiagnostics().error("ntfy: auto-handler initialization failed", e);
    }
    this.delegate = built;
  }

  @Override
  public void publish(LogRecord record) {
    if (delegate != null) {
      delegate.publish(record);
    }
  }

  @Override
  public void flush() {
    if (delegate != null) {
      delegate.flush();
    }
  }

  @Override
  public void close() {
    if (delegate != null) {
      delegate.close();
    }
  }
}
