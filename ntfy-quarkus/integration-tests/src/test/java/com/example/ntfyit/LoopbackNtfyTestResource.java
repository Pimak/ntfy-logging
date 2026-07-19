package com.example.ntfyit;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A {@link QuarkusTestResourceLifecycleManager} that stands in for a real ntfy server. It starts a
 * loopback {@link HttpServer} on an ephemeral port and hands the app the {@code quarkus.ntfy.url}/
 * {@code quarkus.ntfy.topic} pointing at it.
 *
 * <p>This is the ONE mechanism that works for both test styles: the config map returned by {@link
 * #start()} is applied to the in-JVM {@code @QuarkusTest} AND passed as system properties to the
 * separate process launched by {@code @QuarkusIntegrationTest} (fast-jar or native binary). The
 * loopback server always runs in the test JVM, so the app under test reaches it at {@code
 * 127.0.0.1:<port>} either way.
 *
 * <p>Receipt is recorded into a JVM-global {@link System} property rather than a static field: the
 * Quarkus test framework may load this class and the {@code @QuarkusTest} in different classloaders,
 * and only system properties are guaranteed shared across them within the one test JVM.
 */
public class LoopbackNtfyTestResource implements QuarkusTestResourceLifecycleManager {

  /** JVM-global slot the loopback server writes the received request path into. */
  public static final String RECEIVED_PATH_PROP = "ntfy.it.received.path";

  private static final String TOPIC = "smoke";

  private HttpServer server;

  @Override
  public Map<String, String> start() {
    System.clearProperty(RECEIVED_PATH_PROP);
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("failed to start loopback ntfy server", e);
    }
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

    int port = server.getAddress().getPort();
    return Map.of(
        "quarkus.ntfy.url", "http://127.0.0.1:" + port,
        "quarkus.ntfy.topic", TOPIC);
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  /** The topic configured above; the app publishes to {@code /<topic>}. */
  public static String expectedPath() {
    return "/" + TOPIC;
  }
}
