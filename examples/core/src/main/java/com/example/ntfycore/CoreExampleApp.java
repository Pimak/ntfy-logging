package com.example.ntfycore;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.AlertEvent;
import io.github.pimak.ntfy.core.Diagnostics;
import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PublishResult;

/**
 * Example of driving {@code ntfy-core} programmatically from any JVM application — no logging
 * framework involved. It demonstrates the two public surfaces:
 *
 * <ul>
 *   <li>{@link NtfyClient} for ad-hoc manual notifications (always synchronous by contract — it
 *       returns a {@link PublishResult});
 *   <li>{@link AlertEngine} for error alerting with storm suppression, optionally with
 *       {@linkplain NtfyConfig.Builder#asyncEnabled(boolean) asynchronous delivery} so a slow ntfy
 *       server never blocks the submitting thread. A clean {@link AlertEngine#stop()} drains the
 *       async queue into the suppression count and flushes it as a final digest — pending alerts
 *       are reported, never silently lost.
 * </ul>
 *
 * <p>Run it with {@code NTFY_URL} and {@code NTFY_TOPIC} set. The example logic lives in small
 * static methods so the module's integration tests exercise the very same code paths against a
 * loopback ntfy stand-in.
 */
public final class CoreExampleApp {

  private CoreExampleApp() {}

  // --8<-- [start:main]
  public static void main(String[] args) {
    String url = System.getenv("NTFY_URL");
    String topic = System.getenv("NTFY_TOPIC");
    if (url == null || topic == null) {
      System.err.println("Set NTFY_URL and NTFY_TOPIC to run this example, e.g.");
      System.err.println("  NTFY_URL=https://ntfy.sh NTFY_TOPIC=my-topic java ... CoreExampleApp");
      return;
    }

    // 1. A manual notification through NtfyClient. notify(..) never throws — inspect the result.
    PublishResult result = sendManualNotification(baseConfig(url, topic));
    System.out.println("manual notification: " + (result.success() ? "delivered" : "failed"));

    // 2. Error alerting through AlertEngine, with async (non-blocking) delivery enabled.
    AlertEngine engine = new AlertEngine(asyncConfig(url, topic), new StderrDiagnostics());
    engine.start();
    reportCheckoutFailure(engine);
    // A clean stop drains queued-but-unsent alerts into the storm digest before releasing the
    // HTTP resources, so nothing submitted above can be lost silently.
    engine.stop();
    System.out.println(
        "engine counters: published=" + engine.counters().published()
            + " suppressed=" + engine.counters().suppressed()
            + " failed=" + engine.counters().failed());
  }
  // --8<-- [end:main]

  /** The minimal configuration: endpoint + topic. Add {@code .token(..)} for an auth-protected topic. */
  static NtfyConfig baseConfig(String url, String topic) {
    return NtfyConfig.builder().url(url).topic(topic).build();
  }

  /**
   * Delivery offloaded to the engine's bounded queue — the default since 2.0, so no flag is set:
   * {@code submit(..)} returns immediately and a daemon worker ({@code ntfy-alert-delivery}) does
   * the blocking send.
   */
  static NtfyConfig asyncConfig(String url, String topic) {
    return NtfyConfig.builder()
        .url(url)
        .topic(topic)
        .appName("core-example")
        .asyncQueueCapacity(256)
        .build();
  }

  /**
   * The explicit 2.0 opt-out: {@code asyncEnabled(false)} restores synchronous, inline delivery —
   * {@code submit(..)} blocks the calling thread until the HTTP exchange completes. The guarantee a
   * short-lived process (batch job, CLI) may prefer, since the publish is done when the call
   * returns.
   */
  static NtfyConfig syncConfig(String url, String topic) {
    return NtfyConfig.builder()
        .url(url)
        .topic(topic)
        .appName("core-example")
        .asyncEnabled(false)
        .build();
  }

  /** Ad-hoc notification: try-with-resources releases the client's HTTP resources deterministically. */
  static PublishResult sendManualNotification(NtfyConfig config) {
    try (NtfyClient client = new NtfyClient(config)) {
      return client.notify("Batch complete", "12,304 rows processed");
    }
  }

  /**
   * What a hand-rolled adapter does: snapshot the failure as a framework-neutral {@link AlertEvent}
   * and submit it. The engine applies exclusion, rate-limiting, payload assembly, and delivery.
   */
  static void reportCheckoutFailure(AlertEngine engine) {
    engine.submit(
        AlertEvent.of(
            "com.example.shop.CheckoutService",
            "Payment gateway rejected order #4711",
            System.currentTimeMillis()));
  }

  /**
   * Engine self-diagnostics must never route back through the pipeline that publishes alerts, so
   * this example sends them to stderr. Messages are fixed, credential-safe strings.
   */
  static final class StderrDiagnostics implements Diagnostics {
    @Override
    public void info(String msg) {
      System.err.println("[ntfy] " + msg);
    }

    @Override
    public void warn(String msg) {
      System.err.println("[ntfy] WARN " + msg);
    }

    @Override
    public void error(String msg, Throwable t) {
      System.err.println("[ntfy] ERROR " + msg + " (" + t + ")");
    }
  }
}
