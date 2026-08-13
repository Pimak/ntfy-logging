package com.example.ntfycore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import com.example.ntfytestkit.NtfyEngineThreads;
import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.AlertEvent;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Clean-shutdown behavior of the example's {@link AlertEngine} usage:
 *
 * <ul>
 *   <li>synchronous mode — {@code stop()} flushes the accumulated suppression count as one final
 *       digest before releasing the HTTP resources, long before the digest timer would fire;
 *   <li>asynchronous mode — alerts still queued (or in flight) at {@code stop()} fold into that
 *       same shutdown digest, so a submitted alert is reported, never silently lost;
 *   <li>in both modes, no engine thread survives {@code stop()}.
 * </ul>
 */
class CoreEngineCleanShutdownIT {

  @Test
  void syncStopFlushesTheSuppressedCountAsOneFinalDigest() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      NtfyConfig config =
          NtfyConfig.builder()
              .url(server.url())
              .topic("alerts")
              .appName("core-example")
              .maxAlertsPerWindow(1)
              // Long window: the digest below can only come from the stop() flush, never the timer.
              .suppressionWindow(Duration.ofSeconds(60))
              .build();
      AlertEngine engine = new AlertEngine(config, new CoreExampleApp.StderrDiagnostics());
      engine.start();

      for (int i = 0; i < 3; i++) {
        engine.submit(
            AlertEvent.of(
                "com.example.shop.CheckoutService", "boom " + i, System.currentTimeMillis()));
      }
      engine.stop();

      // Request #0 is the one allowed alert; request #1 is the shutdown digest for the other two.
      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(server.received()).hasSize(2);
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");
      assertThat(engine.counters().suppressed()).isEqualTo(2);

      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive stop()")
          .isTrue();
    }
  }

  @Test
  void asyncStopNeverLosesQueuedOrInFlightAlerts() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      // Hold the FIRST response only: the in-flight alert stays un-acknowledged while the
      // shutdown digest (a later request) is answered normally.
      server.holdFirstResponses(1);
      AlertEngine engine =
          new AlertEngine(
              CoreExampleApp.asyncConfig(server.url(), "alerts"),
              new CoreExampleApp.StderrDiagnostics());
      engine.start();

      // Alert #1 is picked up by the single delivery worker and blocks on the held response;
      // alert #2 waits behind it in the bounded queue.
      engine.submit(
          AlertEvent.of("com.example.shop.CheckoutService", "boom 1", System.currentTimeMillis()));
      engine.submit(
          AlertEvent.of("com.example.shop.CheckoutService", "boom 2", System.currentTimeMillis()));
      server.awaitRequest(0, Duration.ofSeconds(5));

      // Clean shutdown: the interrupted in-flight send and the drained queued alert both fold
      // into the suppression count, which the final synchronous flush publishes as a digest.
      engine.stop();

      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");
      assertThat(engine.counters().published())
          .as("only the digest was acknowledged")
          .isEqualTo(1);

      server.releaseHeldResponses();
      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive stop()")
          .isTrue();
    }
  }
}
