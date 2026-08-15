package com.example.ntfylog4j2;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Test;

/**
 * Proves the synchronous-vs-asynchronous delivery difference through the example's own {@code
 * log4j2.xml} ({@code async} attribute), deterministically: the loopback server holds its response
 * open, so a synchronous {@code log.error(..)} is observably still blocked once the request has
 * arrived, while with {@code async=true} the very same call has already returned and the blocking
 * send happens on the {@code ntfy-alert-delivery} daemon worker.
 */
class Log4j2SyncVsAsyncIT {

  @Test
  void syncErrorLogBlocksTheLoggingThreadUntilTheServerResponds() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      server.holdFirstResponses(1);
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              // ntfy.async=false is the explicit 2.0 opt-out: this leg proves synchronous delivery.
              Map.of("ntfy.url", server.url(), "ntfy.topic", "alerts", "ntfy.async", "false"));
      try {
        Logger log = context.getLogger("com.example.shop.CheckoutService");
        CompletableFuture<Void> logging =
            CompletableFuture.runAsync(() -> log.error("payment gateway timed out"));

        // The publish has reached the server but its response is held: the synchronous appender is
        // necessarily still blocking the logging thread inside the HTTP send.
        server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(logging).isNotDone();

        server.releaseHeldResponses();
        logging.get(5, TimeUnit.SECONDS);
      } finally {
        context.stop();
      }
    }
  }

  @Test
  void asyncErrorLogReturnsImmediatelyAndAWorkerDeliversInTheBackground()
      throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      server.holdFirstResponses(1);
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of(
                  "ntfy.url", server.url(),
                  "ntfy.topic", "alerts",
                  "ntfy.async", "true"));
      try {
        Logger log = context.getLogger("com.example.shop.CheckoutService");
        // Returns while the server has not answered anything yet — with async=true the logging
        // thread never waits on the ntfy server.
        log.error("payment gateway timed out");

        LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.path()).isEqualTo("/alerts");
        server.releaseHeldResponses();
      } finally {
        context.stop();
      }
    }
  }
}
