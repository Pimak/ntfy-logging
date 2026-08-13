package com.example.ntfylogback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.example.ntfytestkit.LoopbackNtfyServer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Functional test of the example's base path, driven through the example's own {@code logback.xml}:
 * an ordinary ERROR log (message + exception) is published to the configured topic with the
 * exception chain in the body.
 */
class LogbackExampleAlertIT {

  @Test
  void errorLogBecomesANtfyAlert() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of("NTFY_URL", server.url(), "NTFY_TOPIC", "alerts"));
      try {
        Logger log = context.getLogger("com.example.shop.CheckoutService");
        log.error("Payment gateway timed out", new IllegalStateException("kaboom"));

        LoopbackNtfyServer.ReceivedRequest request =
            server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.path()).isEqualTo("/alerts");
        assertThat(request.header("Priority")).isEqualTo("high");
        assertThat(request.body())
            .contains("Payment gateway timed out")
            .contains("com.example.shop.CheckoutService")
            .contains("IllegalStateException")
            .contains("kaboom");
      } finally {
        context.stop();
      }
    }
  }
}
