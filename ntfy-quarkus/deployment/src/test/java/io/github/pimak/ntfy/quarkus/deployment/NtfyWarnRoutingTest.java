package io.github.pimak.ntfy.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.QuarkusUnitTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Boots a real (in-JVM) Quarkus application with {@code quarkus.ntfy.warn-topic} set and asserts a
 * WARNING record reaches the WARN topic — end-to-end proof of the only chain the other Quarkus
 * tests cannot cover: {@code quarkus.ntfy.warn-*} &rarr; {@code NtfyRuntimeConfig} &rarr; {@code
 * NtfyConfigFactory} &rarr; the engine's warn route &rarr; the installed JUL handler's gate.
 *
 * <p>A separate class from {@code NtfyLogHandlerTest} because {@link QuarkusUnitTest} pins config
 * per application, and that test deliberately boots WITHOUT a warn topic so it keeps proving the
 * ERROR-only default.
 *
 * <p>Receipt is signalled through a JVM-global {@link System} property for the same
 * classloader reason documented on {@code NtfyLogHandlerTest}.
 */
class NtfyWarnRoutingTest {

  static final String RECEIVED_PATH_PROP = "ntfy.test.warnrouting.received.path";

  static final HttpServer SERVER = startServer();

  private static HttpServer startServer() {
    try {
      System.clearProperty(RECEIVED_PATH_PROP);
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            System.setProperty(RECEIVED_PATH_PROP, exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("failed to start loopback server", e);
    }
  }

  @RegisterExtension
  static final QuarkusUnitTest TEST =
      new QuarkusUnitTest()
          .withEmptyApplication()
          .overrideConfigKey(
              "quarkus.ntfy.url", "http://127.0.0.1:" + SERVER.getAddress().getPort())
          .overrideConfigKey("quarkus.ntfy.topic", "alerts")
          .overrideConfigKey("quarkus.ntfy.warn-topic", "alerts-warn")
          .overrideConfigKey("quarkus.ntfy.max-alerts-per-window", "10");

  @Test
  void warningLogIsPublishedToTheWarnTopic() throws InterruptedException {
    // A non-self logger name (the engine always excludes io.github.pimak.ntfy.*).
    Logger.getLogger("com.example.alert").log(Level.WARNING, "disk almost full");

    assertThat(waitForRequest())
        .as("a WARNING record should reach the configured warn topic")
        .isEqualTo("/alerts-warn");
  }

  private static String waitForRequest() throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      String path = System.getProperty(RECEIVED_PATH_PROP);
      if (path != null) {
        return path;
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    return null;
  }
}
