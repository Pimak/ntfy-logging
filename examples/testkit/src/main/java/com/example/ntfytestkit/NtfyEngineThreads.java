package com.example.ntfytestkit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Detects the alert engine's worker threads ({@code ntfy-alert-http}, {@code ntfy-alert-digest},
 * {@code ntfy-alert-delivery}, and the JDK HttpClient selector), so the examples' clean-shutdown
 * tests can assert that stopping the application releases every engine resource.
 */
public final class NtfyEngineThreads {

  private NtfyEngineThreads() {}

  /** True while any engine-owned thread is still alive in this JVM. */
  public static boolean anyAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(
            t ->
                t.isAlive()
                    && (t.getName().startsWith("ntfy-alert-http")
                        || t.getName().startsWith("ntfy-alert-digest")
                        || t.getName().startsWith("ntfy-alert-delivery")
                        || (t.getName().contains("HttpClient-")
                            && t.getName().contains("SelectorManager"))));
  }

  /**
   * Waits (bounded by {@code timeout}) for every engine-owned thread to exit — {@code stop()} only
   * requests interruption, so threads can be observably alive for a short window afterwards.
   *
   * @return true when no engine thread is left alive
   */
  public static boolean awaitNoneAlive(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (anyAlive() && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(25);
    }
    return !anyAlive();
  }
}
