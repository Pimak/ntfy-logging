package io.github.pimak.ntfy.jul;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Test support: a loopback {@link HttpServer} standing in for an ntfy server, recording the path of
 * every request it receives. Shared by the handler/installer/auto-handler tests so the server
 * plumbing lives once.
 */
final class LoopbackNtfyServer implements AutoCloseable {

  private final HttpServer server;
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
  private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

  LoopbackNtfyServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          // Drain the body (the client needs a clean response either way) and record it BEFORE the
          // path: waitForRequest() polls receivedPaths, so recording the path last is what makes
          // "a request arrived" imply "its body is already readable" for the caller.
          String requestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          receivedBodies.add(requestBody);
          receivedPaths.add(exchange.getRequestURI().getPath());
          byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<String> receivedPaths() {
    return receivedPaths;
  }

  /** The raw request bodies received, in arrival order — i.e. the alert bodies ntfy would show. */
  List<String> receivedBodies() {
    return receivedBodies;
  }

  /** Polls up to ~5s for at least one request to have arrived. */
  boolean waitForRequest() throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (!receivedPaths.isEmpty()) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return !receivedPaths.isEmpty();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
