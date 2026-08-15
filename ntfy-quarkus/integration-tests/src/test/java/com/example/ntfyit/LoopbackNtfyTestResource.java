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

  /**
   * JVM-global slot the loopback server writes the received request BODY into — i.e. the alert text
   * ntfy would display. Same system-property mechanism (and same cross-classloader reason) as
   * {@link #RECEIVED_PATH_PROP}; it is written BEFORE the path so that a test polling on the path
   * having arrived can always read the matching body.
   */
  public static final String RECEIVED_BODY_PROP = "ntfy.it.received.body";

  /**
   * JVM-global flag ({@code "true"}/absent): while set, the loopback server records each request on
   * arrival but holds its response open (bounded by a 15 s safety timeout). The async test uses it
   * to prove {@code quarkus.ntfy.async=true} keeps {@code /boom} non-blocking — the request thread
   * returns while the ntfy response is still outstanding. A system property (not a latch) for the
   * same classloader reason as {@link #RECEIVED_PATH_PROP}.
   */
  public static final String HOLD_RESPONSES_PROP = "ntfy.it.hold.responses";

  private static final long HOLD_SAFETY_TIMEOUT_MILLIS = 15_000L;

  private static final String TOPIC = "smoke";

  private HttpServer server;

  @Override
  public Map<String, String> start() {
    System.clearProperty(RECEIVED_PATH_PROP);
    System.clearProperty(RECEIVED_BODY_PROP);
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("failed to start loopback ntfy server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          // Body first, path last: waitForReceipt() polls the path, so recording it last is what
          // makes "a publish arrived" imply "its body is already readable".
          System.setProperty(
              RECEIVED_BODY_PROP,
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          System.setProperty(RECEIVED_PATH_PROP, exchange.getRequestURI().getPath());
          awaitHoldReleased();
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

  /** Polls {@link #HOLD_RESPONSES_PROP} until cleared, bounded so a test can never hang the run. */
  private static void awaitHoldReleased() {
    long deadline = System.currentTimeMillis() + HOLD_SAFETY_TIMEOUT_MILLIS;
    while (Boolean.getBoolean(HOLD_RESPONSES_PROP) && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
