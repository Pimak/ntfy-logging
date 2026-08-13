package com.example.ntfyjul;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.github.pimak.ntfy.jul.NtfyJulHandler;
import io.github.pimak.ntfy.jul.NtfyJulInstaller;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Functional test of the example's base path, driven through the real zero-code install ({@code
 * -Dntfy.*} system properties + {@link NtfyJulInstaller}): an ordinary SEVERE log (message +
 * exception) is published to the configured topic with the exception chain in the body. The
 * handler is installed on the {@code com.example.shop} subtree — the documented way to scope
 * alerting to part of an application.
 */
class JulExampleAlertIT {

  @Test
  void severeLogBecomesANtfyAlert() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        AmbientNtfyProperties props = new AmbientNtfyProperties()) {
      props.set("ntfy.url", server.url()).set("ntfy.topic", "alerts");
      Logger scope = Logger.getLogger("com.example.shop");
      NtfyJulHandler handler = NtfyJulInstaller.install(scope).orElseThrow();
      try {
        Logger checkout = Logger.getLogger("com.example.shop.CheckoutService");
        checkout.log(Level.SEVERE, "Payment gateway timed out", new IllegalStateException("kaboom"));

        LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.path()).isEqualTo("/alerts");
        assertThat(request.header("Priority")).isEqualTo("high");
        assertThat(request.body())
            .contains("Payment gateway timed out")
            .contains("com.example.shop.CheckoutService")
            .contains("IllegalStateException")
            .contains("kaboom");
      } finally {
        NtfyJulInstaller.uninstall(scope, handler);
      }
    }
  }
}
