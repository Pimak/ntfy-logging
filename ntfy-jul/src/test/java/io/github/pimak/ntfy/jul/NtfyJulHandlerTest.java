package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.NtfyConfig;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full runtime path: an {@link NtfyJulHandler} built via {@link
 * NtfyJulHandler#forConfig} (so the factory's engine wiring is covered too), pointed at a loopback
 * server standing in for ntfy.
 */
class NtfyJulHandlerTest {

  private LoopbackNtfyServer server;

  @BeforeEach
  void startServer() {
    server = new LoopbackNtfyServer();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void severeRecordIsPublishedToNtfy() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder().url(server.baseUrl()).topic("alerts").maxAlertsPerWindow(10).build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "kaboom");
      record.setLoggerName("com.example.Boom");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void severeRecordIsPublishedInAsyncMode() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(server.baseUrl())
            .topic("alerts")
            .maxAlertsPerWindow(10)
            .asyncEnabled(true)
            .build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "kaboom-async");
      record.setLoggerName("com.example.Boom");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      // The publish is offloaded to the ntfy-alert-delivery worker; it still reaches the server.
      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void belowSevereRecordIsIgnored() throws InterruptedException {
    NtfyConfig cfg = NtfyConfig.builder().url(server.baseUrl()).topic("alerts").build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord record = new LogRecord(Level.WARNING, "just a warning");
      record.setLoggerName("com.example.Warn");
      handler.publish(record);

      // Give any (erroneous) async publish a moment; nothing should arrive.
      TimeUnit.MILLISECONDS.sleep(300);
      assertThat(server.receivedPaths()).isEmpty();
    } finally {
      handler.close();
    }
  }
}
