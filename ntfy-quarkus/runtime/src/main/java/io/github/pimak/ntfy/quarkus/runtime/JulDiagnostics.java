package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.Diagnostics;

/**
 * {@link Diagnostics} sink for the Quarkus extension that writes to {@link System#err}, never back
 * into any logging framework. Routing engine self-diagnostics through the log system would create a
 * feedback loop (a JUL handler that logs, whose logs feed the handler); {@code System.err} breaks
 * it. The core engine already guarantees these messages are generic and never carry a credential;
 * this sink simply relays them.
 */
final class JulDiagnostics implements Diagnostics {

  private static final String PREFIX = "[ntfy] ";

  @Override
  public void info(String message) {
    System.err.println(PREFIX + message);
  }

  @Override
  public void warn(String message) {
    System.err.println(PREFIX + "WARN: " + message);
  }

  @Override
  public void error(String message, Throwable throwable) {
    System.err.println(PREFIX + "ERROR: " + message);
    if (throwable != null) {
      throwable.printStackTrace();
    }
  }
}
