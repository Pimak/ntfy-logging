package com.example.ntfymicronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import com.example.ntfytestkit.NtfyEngineThreads;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Clean-shutdown behavior of the booted example application: closing the Micronaut context runs the
 * module's {@code @PreDestroy}, which stops and detaches the ntfy appender — flushing the pending
 * storm digest and, in async mode, first folding queued-or-in-flight alerts into that digest so
 * nothing is silently lost. No engine thread survives the shutdown.
 */
class MicronautCleanShutdownIT {

  @Test
  void closingTheContextFlushesTheSuppressedCountAsOneFinalDigest() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      ApplicationContext context =
          ExampleApplications.run(
              server,
              Map.of(
                  "ntfy.max-alerts-per-window", 1,
                  // Explicit 2.0 opt-out (the engine default is async): with synchronous delivery
                  // request #0 is necessarily the allowed alert and #1 the shutdown digest.
                  "ntfy.async", false,
                  // Long window: the digest below can only come from the shutdown flush.
                  "ntfy.suppression-window", "60s"));
      OrderService orders = context.getBean(OrderService.class);
      for (int i = 0; i < 3; i++) {
        orders.placeOrder("order-" + i);
      }

      context.close();

      // Request #0 is the one allowed alert; request #1 is the shutdown digest for the other two.
      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(server.received()).hasSize(2);
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive the context close")
          .isTrue();
    }
  }

  @Test
  void asyncQueuedAlertsFoldIntoTheShutdownDigestInsteadOfBeingLost() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      // Hold the FIRST response only: the in-flight alert stays un-acknowledged while the
      // shutdown digest (a later request) is answered normally.
      server.holdFirstResponses(1);
      ApplicationContext context =
          ExampleApplications.run(
              server, Map.of("ntfy.async", true, "ntfy.suppression-window", "60s"));
      OrderService orders = context.getBean(OrderService.class);

      // Alert #1 is picked up by the single delivery worker and blocks on the held response;
      // alert #2 waits behind it in the bounded queue.
      orders.placeOrder("order-1");
      orders.placeOrder("order-2");
      server.awaitRequest(0, Duration.ofSeconds(5));

      context.close();

      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      server.releaseHeldResponses();
      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive the context close")
          .isTrue();
    }
  }
}
