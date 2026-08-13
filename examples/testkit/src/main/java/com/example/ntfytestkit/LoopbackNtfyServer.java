package com.example.ntfytestkit;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback stand-in for a real ntfy server, shared by the example applications' integration
 * tests. It listens on an ephemeral 127.0.0.1 port, records every request it receives (path,
 * headers, body), and answers {@code 200 {"id":"1"}} like ntfy does.
 *
 * <p>Two features make the sync-vs-async and clean-shutdown tests deterministic without any
 * timing-based sleeps:
 *
 * <ul>
 *   <li>{@link #holdFirstResponses(int)} keeps the response of the first {@code n} requests open
 *       until {@link #releaseHeldResponses()} — a synchronous publisher is provably still blocked
 *       while the request has arrived but its response is held, and an asynchronous one provably
 *       returned. Requests are recorded on arrival, before the hold, so {@link #awaitRequest} works
 *       on held requests too. A 15-second safety timeout guarantees a forgotten release can never
 *       hang a test run.
 *   <li>{@link #awaitRequest(int, Duration)} polls until the n-th request has arrived, which is how
 *       tests observe background (async worker) and shutdown-flush deliveries.
 * </ul>
 */
public final class LoopbackNtfyServer implements AutoCloseable {

  /** One recorded publish: request path (i.e. {@code /<topic>}), headers, and UTF-8 body. */
  public record ReceivedRequest(String path, Map<String, List<String>> headers, String body) {

    /** First value of {@code name} (canonical form, e.g. {@code "Title"}), or {@code null}. */
    public String header(String name) {
      List<String> values = headers.get(name);
      return values == null || values.isEmpty() ? null : values.get(0);
    }
  }

  private static final Duration HOLD_SAFETY_TIMEOUT = Duration.ofSeconds(15);

  private final HttpServer server;
  private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger requestIndex = new AtomicInteger();
  private final CountDownLatch release = new CountDownLatch(1);

  // volatile: written by the test thread (holdFirstResponses) and read by server exchange threads.
  private volatile int holdFirst;

  public LoopbackNtfyServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("failed to start loopback ntfy server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          int index = requestIndex.getAndIncrement();
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          Map<String, List<String>> headers = new LinkedHashMap<>();
          exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
          received.add(
              new ReceivedRequest(
                  exchange.getRequestURI().getPath(), Map.copyOf(headers), body));
          if (index < holdFirst) {
            awaitReleaseQuietly();
          }
          byte[] response = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    // A small pool (not the default calling-thread executor) so a held response never blocks a
    // later request — the clean-shutdown tests need the digest publish answered while the first
    // alert's response is still held open.
    server.setExecutor(
        java.util.concurrent.Executors.newFixedThreadPool(
            4,
            r -> {
              Thread t = new Thread(r, "loopback-ntfy");
              t.setDaemon(true);
              return t;
            }));
    server.start();
  }

  /** Base URL of the stand-in, e.g. {@code http://127.0.0.1:49152} — what {@code url} points at. */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Holds the response of the first {@code n} requests open until {@link #releaseHeldResponses()}
   * (or the 15 s safety timeout). Call before the requests are triggered. Later requests — e.g. the
   * digest flush a clean shutdown publishes — are answered immediately.
   */
  public void holdFirstResponses(int n) {
    this.holdFirst = n;
  }

  /** Lets every held response complete. Idempotent. */
  public void releaseHeldResponses() {
    release.countDown();
  }

  /** Snapshot of all requests received so far, in arrival order. */
  public List<ReceivedRequest> received() {
    return List.copyOf(received);
  }

  /**
   * Waits until the request with zero-based {@code index} has arrived and returns it. Held requests
   * are visible here on arrival, before their response is released.
   *
   * @throws AssertionError when the request does not arrive within {@code timeout}
   */
  public ReceivedRequest awaitRequest(int index, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (received.size() <= index) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "expected request #" + index + " within " + timeout + " but received only "
                + received.size() + ": " + received);
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
    return received.get(index);
  }

  @Override
  public void close() {
    releaseHeldResponses();
    server.stop(0);
  }

  private void awaitReleaseQuietly() {
    try {
      release.await(HOLD_SAFETY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
