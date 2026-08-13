package com.example.ntfyjul;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.github.pimak.ntfy.jul.NtfyJulHandler;
import io.github.pimak.ntfy.jul.NtfyJulInstaller;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Proves the synchronous-vs-asynchronous delivery difference through the example's zero-code
 * install ({@code -Dntfy.async}), deterministically: the loopback server holds its response open,
 * so a synchronous SEVERE log is observably still blocked once the request has arrived, while with
 * {@code ntfy.async=true} the very same call has already returned and the blocking send happens on
 * the {@code ntfy-alert-delivery} daemon worker.
 */
class JulSyncVsAsyncIT {

  @Test
  void syncSevereLogBlocksTheLoggingThreadUntilTheServerResponds() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        AmbientNtfyProperties props = new AmbientNtfyProperties()) {
      props.set("ntfy.url", server.url()).set("ntfy.topic", "alerts");
      server.holdFirstResponses(1);
      Logger scope = Logger.getLogger("com.example.shop");
      NtfyJulHandler handler = NtfyJulInstaller.install(scope).orElseThrow();
      try {
        Logger checkout = Logger.getLogger("com.example.shop.CheckoutService");
        CompletableFuture<Void> logging =
            CompletableFuture.runAsync(
                () -> checkout.log(Level.SEVERE, "payment gateway timed out"));

        // The publish has reached the server but its response is held: the synchronous handler is
        // necessarily still blocking the logging thread inside the HTTP send.
        server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(logging).isNotDone();

        server.releaseHeldResponses();
        logging.get(5, TimeUnit.SECONDS);
      } finally {
        NtfyJulInstaller.uninstall(scope, handler);
      }
    }
  }

  @Test
  void asyncSevereLogReturnsImmediatelyAndAWorkerDeliversInTheBackground()
      throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        AmbientNtfyProperties props = new AmbientNtfyProperties()) {
      props
          .set("ntfy.url", server.url())
          .set("ntfy.topic", "alerts")
          .set("ntfy.async", "true");
      server.holdFirstResponses(1);
      Logger scope = Logger.getLogger("com.example.shop");
      NtfyJulHandler handler = NtfyJulInstaller.install(scope).orElseThrow();
      try {
        // Returns while the server has not answered anything yet — with ntfy.async=true the
        // logging thread never waits on the ntfy server.
        Logger.getLogger("com.example.shop.CheckoutService")
            .log(Level.SEVERE, "payment gateway timed out");

        LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.path()).isEqualTo("/alerts");
        server.releaseHeldResponses();
      } finally {
        NtfyJulInstaller.uninstall(scope, handler);
      }
    }
  }
}
