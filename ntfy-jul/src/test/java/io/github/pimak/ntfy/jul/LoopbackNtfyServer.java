package io.github.pimak.ntfy.jul;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Test support: a loopback {@link HttpServer} standing in for an ntfy server, recording the path,
 * body and headers of every request it receives. Shared by the handler/installer/auto-handler tests
 * and the header/digest ITs so the server plumbing lives once. It exists because ntfy-jul's
 * dependency allowlist admits no HTTP-mocking library — see the enforcer rule in its pom.
 */
final class LoopbackNtfyServer implements AutoCloseable {

  private final HttpServer server;
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
  private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
  private final List<Headers> receivedHeaders = new CopyOnWriteArrayList<>();

  LoopbackNtfyServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          // Snapshot the headers into our own Headers instance rather than retaining the
          // exchange's: that one is recycled when close() returns below, so a stored reference
          // would read back empty from the test thread. Headers.putAll normalizes the keys, which
          // is what keeps the case-insensitive getFirst("Click")/containsKey("Actions") lookups in
          // the ITs honest.
          Headers headerSnapshot = new Headers();
          headerSnapshot.putAll(exchange.getRequestHeaders());
          receivedHeaders.add(headerSnapshot);
          // Drain the body (the client needs a clean response either way) and record it, like the
          // headers above, BEFORE the path: waitForRequests() polls receivedPaths, so recording the
          // path LAST is what makes "a request arrived" imply "its body and headers are already
          // readable" for the caller. Anything new recorded per request goes above this line.
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

  /**
   * The request headers received, in arrival order and index-aligned with {@link #receivedBodies()}
   * — i.e. the {@code Priority}/{@code Tags}/{@code Click}/{@code Actions} headers ntfy would act
   * on.
   */
  List<Headers> receivedHeaders() {
    return receivedHeaders;
  }

  /** Polls for at least one request to have arrived. */
  boolean waitForRequest() throws InterruptedException {
    return waitForRequests(1);
  }

  /**
   * Polls up to ~10s for at least {@code count} requests to have arrived. The digest IT needs this
   * rather than {@link #waitForRequest()}: the notification it is about is the follow-up the digest
   * timer publishes one suppression window after the burst, not the first alert. The budget is
   * generous on purpose — it is only ever spent in full when the test is already failing.
   */
  boolean waitForRequests(int count) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      if (receivedPaths.size() >= count) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return receivedPaths.size() >= count;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
