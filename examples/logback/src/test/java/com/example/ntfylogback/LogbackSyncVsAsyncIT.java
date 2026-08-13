package com.example.ntfylogback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.example.ntfytestkit.LoopbackNtfyServer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves the synchronous-vs-asynchronous delivery difference through the example's own {@code
 * logback.xml} ({@code <async>} flag), deterministically: the loopback server holds its response
 * open, so a synchronous {@code log.error(..)} is observably still blocked once the request has
 * arrived, while with {@code async=true} the very same call has already returned and the blocking
 * send happens on the {@code ntfy-alert-delivery} daemon worker.
 */
class LogbackSyncVsAsyncIT {

  @Test
  void syncErrorLogBlocksTheLoggingThreadUntilTheServerResponds() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      server.holdFirstResponses(1);
      // NTFY_ASYNC=false is the explicit 2.0 opt-out: this leg proves synchronous delivery.
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of("NTFY_URL", server.url(), "NTFY_TOPIC", "alerts", "NTFY_ASYNC", "false"));
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
              Map.of("NTFY_URL", server.url(), "NTFY_TOPIC", "alerts", "NTFY_ASYNC", "true"));
      try {
        Logger log = context.getLogger("com.example.shop.CheckoutService");
        // Returns while the server has not answered anything yet — with async=true the logging
        // thread never waits on the ntfy server.
        log.error("payment gateway timed out");

        LoopbackNtfyServer.ReceivedRequest request =
            server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.path()).isEqualTo("/alerts");
        server.releaseHeldResponses();
      } finally {
        context.stop();
      }
    }
  }
}
