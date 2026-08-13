package com.example.ntfyjul;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import com.example.ntfytestkit.NtfyEngineThreads;
import io.github.pimak.ntfy.jul.NtfyJulHandler;
import io.github.pimak.ntfy.jul.NtfyJulInstaller;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Clean-shutdown behavior through the example's install path: {@link NtfyJulInstaller#uninstall}
 * (what the app calls on exit; JUL's own LogManager shutdown hook closes handlers the same way)
 * stops the handler's engine, which flushes the pending storm digest — and, in async mode, first
 * folds queued-or-in-flight alerts into that digest so nothing is silently lost. No engine thread
 * survives the uninstall.
 */
class JulCleanShutdownIT {

  @Test
  void uninstallFlushesTheSuppressedCountAsOneFinalDigest() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        AmbientNtfyProperties props = new AmbientNtfyProperties()) {
      props
          .set("ntfy.url", server.url())
          .set("ntfy.topic", "alerts")
          .set("ntfy.max-alerts-per-window", "1")
          // Explicit 2.0 opt-out: this leg proves the synchronous shutdown contract.
          .set("ntfy.async", "false")
          // Long window: the digest below can only come from the shutdown flush.
          .set("ntfy.suppression-window", "60000");
      Logger scope = Logger.getLogger("com.example.shop");
      NtfyJulHandler handler = NtfyJulInstaller.install(scope).orElseThrow();
      Logger checkout = Logger.getLogger("com.example.shop.CheckoutService");
      for (int i = 0; i < 3; i++) {
        checkout.log(Level.SEVERE, "boom " + i);
      }

      NtfyJulInstaller.uninstall(scope, handler);

      // Request #0 is the one allowed alert; request #1 is the shutdown digest for the other two.
      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(server.received()).hasSize(2);
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive the uninstall")
          .isTrue();
    }
  }

  @Test
  void asyncQueuedAlertsFoldIntoTheShutdownDigestInsteadOfBeingLost() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        AmbientNtfyProperties props = new AmbientNtfyProperties()) {
      props
          .set("ntfy.url", server.url())
          .set("ntfy.topic", "alerts")
          .set("ntfy.async", "true")
          .set("ntfy.suppression-window", "60000");
      // Hold the FIRST response only: the in-flight alert stays un-acknowledged while the
      // shutdown digest (a later request) is answered normally.
      server.holdFirstResponses(1);
      Logger scope = Logger.getLogger("com.example.shop");
      NtfyJulHandler handler = NtfyJulInstaller.install(scope).orElseThrow();
      Logger checkout = Logger.getLogger("com.example.shop.CheckoutService");

      // Alert #1 is picked up by the single delivery worker and blocks on the held response;
      // alert #2 waits behind it in the bounded queue.
      checkout.log(Level.SEVERE, "boom 1");
      checkout.log(Level.SEVERE, "boom 2");
      server.awaitRequest(0, Duration.ofSeconds(5));

      NtfyJulInstaller.uninstall(scope, handler);

      LoopbackNtfyServer.ReceivedRequest digest = server.awaitRequest(1, Duration.ofSeconds(5));
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      server.releaseHeldResponses();
      assertThat(NtfyEngineThreads.awaitNoneAlive(Duration.ofSeconds(2)))
          .as("no engine thread should survive the uninstall")
          .isTrue();
    }
  }
}
