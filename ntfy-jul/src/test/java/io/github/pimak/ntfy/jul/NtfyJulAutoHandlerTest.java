package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The declarative ({@code logging.properties}) entry point: a no-arg-constructed {@link
 * NtfyJulAutoHandler} self-configures from the ambient environment — here {@code ntfy.*} system
 * properties — and degrades to a safe no-op when nothing is configured.
 */
class NtfyJulAutoHandlerTest {

  private LoopbackNtfyServer server;

  @BeforeEach
  void startServer() {
    server = new LoopbackNtfyServer();
  }

  @AfterEach
  void cleanUp() {
    System.clearProperty("ntfy.url");
    System.clearProperty("ntfy.topic");
    if (server != null) {
      server.close();
    }
  }

  @Test
  void selfConfiguresFromAmbientEnvironmentAndPublishes() throws InterruptedException {
    System.setProperty("ntfy.url", server.baseUrl());
    System.setProperty("ntfy.topic", "auto-alerts");

    NtfyJulAutoHandler handler = new NtfyJulAutoHandler();
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "auto kaboom");
      record.setLoggerName("com.example.Auto");
      handler.publish(record);

      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/auto-alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void unconfiguredEnvironmentYieldsSafeNoOp() {
    NtfyJulAutoHandler handler = new NtfyJulAutoHandler();

    assertThatCode(
            () -> {
              handler.publish(new LogRecord(Level.SEVERE, "dropped"));
              handler.flush();
              handler.close();
            })
        .doesNotThrowAnyException();
    assertThat(server.receivedPaths()).isEmpty();
  }
}
