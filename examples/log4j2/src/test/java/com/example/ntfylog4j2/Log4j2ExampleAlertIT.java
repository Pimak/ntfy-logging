package com.example.ntfylog4j2;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import java.time.Duration;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Test;

/**
 * Functional test of the example's base path, driven through the example's own {@code log4j2.xml}:
 * an ordinary ERROR log (message + exception) is published to the configured topic with the
 * exception chain in the body. Reaching the loopback server at all also proves the {@code <Ntfy>}
 * element resolved through the plugin descriptor ntfy-log4j2 ships.
 */
class Log4j2ExampleAlertIT {

  @Test
  void errorLogBecomesANtfyAlert() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of("ntfy.url", server.url(), "ntfy.topic", "alerts",
                  // Synchronous delivery keeps the assertions ordering-exact and sleep-free:
                  // the publish completes before the logging call returns.
                  "ntfy.async", "false"));
      try {
        Logger log = context.getLogger("com.example.shop.CheckoutService");
        log.error("Payment gateway timed out", new IllegalStateException("kaboom"));

        LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
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

  @Test
  void logsBelowErrorAreNotAlerted() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      LoggerContext context =
          ExampleLoggerContexts.startFromExampleXml(
              Map.of("ntfy.url", server.url(), "ntfy.topic", "alerts",
                  // Synchronous delivery keeps the assertions ordering-exact and sleep-free:
                  // the publish completes before the logging call returns.
                  "ntfy.async", "false"));
      try {
        Logger log = context.getLogger("com.example.shop.CheckoutService");
        // The example references the appender from a <Root level="info">, so INFO/WARN events do
        // reach it — the appender's own ERROR gate is what keeps them off the topic. Delivery is
        // synchronous here, so the trailing error below has fully published by the time it returns:
        // if either line above had alerted it would necessarily be request #0.
        log.info("checkout completed");
        log.warn("payment retried once");
        log.error("Payment gateway timed out");

        LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.body()).contains("Payment gateway timed out");
        assertThat(server.received()).hasSize(1);
      } finally {
        context.stop();
      }
    }
  }
}
