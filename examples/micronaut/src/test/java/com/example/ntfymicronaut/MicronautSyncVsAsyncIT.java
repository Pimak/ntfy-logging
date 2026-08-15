package com.example.ntfymicronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves the synchronous-vs-asynchronous delivery difference on the booted example application
 * ({@code ntfy.async} property), deterministically: the loopback server holds its response open, so
 * a synchronous {@code log.error(..)} inside {@link OrderService} is observably still blocked once
 * the request has arrived, while with {@code ntfy.async=true} the very same call has already
 * returned and the blocking send happens on the {@code ntfy-alert-delivery} daemon worker.
 */
class MicronautSyncVsAsyncIT {

  @Test
  void syncErrorLogBlocksTheCallingThreadUntilTheServerResponds() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        // ntfy.async=false is the explicit 2.0 opt-out (the engine default is true): this leg
        // proves synchronous delivery, so the flag is pinned rather than inherited.
        ApplicationContext context =
            ExampleApplications.run(server, Map.of("ntfy.async", false))) {
      server.holdFirstResponses(1);
      OrderService orders = context.getBean(OrderService.class);

      CompletableFuture<Void> order = CompletableFuture.runAsync(() -> orders.placeOrder("4711"));

      // The publish has reached the server but its response is held: the synchronous appender is
      // necessarily still blocking placeOrder's thread inside the HTTP send.
      server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(order).isNotDone();

      server.releaseHeldResponses();
      order.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void asyncErrorLogReturnsImmediatelyAndAWorkerDeliversInTheBackground()
      throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        ApplicationContext context =
            ExampleApplications.run(server, Map.of("ntfy.async", true))) {
      server.holdFirstResponses(1);

      // Returns while the server has not answered anything yet — with ntfy.async=true the calling
      // thread never waits on the ntfy server.
      context.getBean(OrderService.class).placeOrder("4711");

      LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(request.path()).isEqualTo("/alerts");
      server.releaseHeldResponses();
    }
  }
}
