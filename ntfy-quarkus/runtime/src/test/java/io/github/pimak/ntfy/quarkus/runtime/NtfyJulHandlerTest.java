package io.github.pimak.ntfy.quarkus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full runtime path: an {@link NtfyJulHandler} wrapping a real (started) {@link
 * AlertEngine}, pointed at a loopback {@link HttpServer} standing in for an ntfy server.
 */
class NtfyJulHandlerTest {

  private HttpServer server;
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          receivedPaths.add(exchange.getRequestURI().getPath());
          // Drain the body so the client sees a clean response.
          exchange.getRequestBody().readAllBytes();
          byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Test
  void severeRecordIsPublishedToNtfy() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder().url(baseUrl()).topic("alerts").maxAlertsPerWindow(10).build();
    AlertEngine engine = new AlertEngine(cfg, new JulDiagnostics());
    engine.start();
    NtfyJulHandler handler = new NtfyJulHandler(engine);
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "kaboom");
      record.setLoggerName("com.example.Boom");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      assertThat(waitForRequest()).isTrue();
      assertThat(receivedPaths).contains("/alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void severeRecordIsPublishedInAsyncMode() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(baseUrl())
            .topic("alerts")
            .maxAlertsPerWindow(10)
            .asyncEnabled(true)
            .build();
    AlertEngine engine = new AlertEngine(cfg, new JulDiagnostics());
    engine.start();
    NtfyJulHandler handler = new NtfyJulHandler(engine);
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "kaboom-async");
      record.setLoggerName("com.example.Boom");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      // The publish is offloaded to the ntfy-alert-delivery worker; it still reaches the server.
      assertThat(waitForRequest()).isTrue();
      assertThat(receivedPaths).contains("/alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void belowSevereRecordIsIgnored() throws InterruptedException {
    NtfyConfig cfg = NtfyConfig.builder().url(baseUrl()).topic("alerts").build();
    AlertEngine engine = new AlertEngine(cfg, new JulDiagnostics());
    engine.start();
    NtfyJulHandler handler = new NtfyJulHandler(engine);
    try {
      LogRecord record = new LogRecord(Level.WARNING, "just a warning");
      record.setLoggerName("com.example.Warn");
      handler.publish(record);

      // Give any (erroneous) async publish a moment; nothing should arrive.
      TimeUnit.MILLISECONDS.sleep(300);
      assertThat(receivedPaths).isEmpty();
    } finally {
      handler.close();
    }
  }

  private boolean waitForRequest() throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (!receivedPaths.isEmpty()) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return !receivedPaths.isEmpty();
  }
}
