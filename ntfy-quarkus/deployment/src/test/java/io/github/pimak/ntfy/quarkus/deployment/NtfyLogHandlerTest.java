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
 * Boots a real (in-JVM) Quarkus application with the ntfy extension active and asserts that a
 * SEVERE log record is published to a loopback ntfy stand-in — end-to-end proof that the {@code
 * LogHandlerBuildItem} wiring installs the handler and the RUNTIME_INIT recorder starts a working
 * engine from {@code quarkus.ntfy.*} config.
 *
 * <p>Receipt is signalled through a JVM-global {@link System} property rather than a static field:
 * QuarkusUnitTest may load this test class in more than one classloader, so a plain static
 * collection populated by the server callback would not be the same instance the {@code @Test}
 * assertion reads. System properties are shared across classloaders in the one JVM.
 */
class NtfyLogHandlerTest {

  static final String RECEIVED_PATH_PROP = "ntfy.test.received.path";

  // Loopback ntfy stand-in, started before Quarkus boots so its port can be fed into config.
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
          .overrideConfigKey("quarkus.ntfy.max-alerts-per-window", "10");

  @Test
  void severeLogIsPublishedThroughInstalledHandler() throws InterruptedException {
    // A non-self logger name (the engine always excludes io.github.pimak.ntfy.*).
    Logger.getLogger("com.example.alert")
        .log(Level.SEVERE, "boom", new IllegalStateException("bad"));

    assertThat(waitForRequest())
        .as("loopback ntfy server should receive a publish")
        .isEqualTo("/alerts");
  }

  private static String waitForRequest() throws InterruptedException {
    for (int i = 0; i < 50 && System.getProperty(RECEIVED_PATH_PROP) == null; i++) {
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return System.getProperty(RECEIVED_PATH_PROP);
  }
}
