package com.example.ntfylogback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.example.ntfytestkit.LoopbackNtfyServer;
import com.example.ntfytestkit.NtfyEngineThreads;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Clean-shutdown behavior through the example's own {@code logback.xml}: stopping the {@link
 * LoggerContext} (what the configured {@code shutdownHook} does on JVM exit) stops the ntfy
 * appender, which flushes the pending storm digest — and, in async mode, first folds
 * queued-or-in-flight alerts into that digest so nothing is silently lost. No engine thread
 * survives the stop.
 */
class LogbackCleanShutdownIT {

  @Test
  void stoppingTheContextFlushesTheSuppressedCountAsOneFinalDigest() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of(
                  "NTFY_URL", server.url(),
                  "NTFY_TOPIC", "alerts",
                  "NTFY_MAX_ALERTS_PER_WINDOW", "1",
                  // Explicit 2.0 opt-out: this leg proves the synchronous shutdown contract.
                  "NTFY_ASYNC", "false",
                  // Long window: the digest below can only come from the shutdown flush.
                  "NTFY_SUPPRESSION_WINDOW", "60000"));
      Logger log = context.getLogger("com.example.shop.CheckoutService");
      for (int i = 0; i < 3; i++) {
        log.error("boom {}", i);
      }

      context.stop();

      // Request #0 is the one allowed alert; request #1 is the shutdown digest for the other two.
      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(server.received()).hasSize(2);
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive the context stop")
          .isTrue();
    }
  }

  @Test
  void asyncQueuedAlertsFoldIntoTheShutdownDigestInsteadOfBeingLost() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      // Hold the FIRST response only: the in-flight alert stays un-acknowledged while the
      // shutdown digest (a later request) is answered normally.
      server.holdFirstResponses(1);
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of(
                  "NTFY_URL", server.url(),
                  "NTFY_TOPIC", "alerts",
                  "NTFY_ASYNC", "true",
                  "NTFY_SUPPRESSION_WINDOW", "60000"));
      Logger log = context.getLogger("com.example.shop.CheckoutService");

      // Alert #1 is picked up by the single delivery worker and blocks on the held response;
      // alert #2 waits behind it in the bounded queue.
      log.error("boom 1");
      log.error("boom 2");
      server.awaitRequest(0, Duration.ofSeconds(5));

      context.stop();

      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      server.releaseHeldResponses();
      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive the context stop")
          .isTrue();
    }
  }
}
