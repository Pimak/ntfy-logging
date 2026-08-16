package io.github.pimak.ntfy.quarkus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PipelineCounters;
import io.github.pimak.ntfy.jul.NtfyJulHandler;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Covers the mechanics of the Micrometer binding in isolation: the meters it registers, and the
 * lazy read through {@link NtfyPipelineCountersHolder} that decouples them from the log handler's
 * lifecycle. The build-time conditional registration and the CDI discovery of this binder are
 * covered by the deployment module's {@code NtfyMetricsBindingTest}, which boots a real Quarkus
 * application with the Micrometer extension.
 */
class NtfyMeterBinderTest {

  private SimpleMeterRegistry registry;
  private LoopbackNtfyServer server;

  /**
   * Held in a field, never as a local: {@link FunctionCounter} keeps only a WEAK reference to the
   * state object it reads through, so a binder that became unreachable mid-test could be collected
   * and every meter would read 0. In production the binder is a CDI singleton, which is what makes
   * the same guarantee there.
   */
  private NtfyMeterBinder binder;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    server = new LoopbackNtfyServer();
    binder = new NtfyMeterBinder();
  }

  @AfterEach
  void tearDown() {
    // The holder is static (see its class comment), so every test must leave it as it found it.
    NtfyPipelineCountersHolder.set(null);
    registry.close();
    server.close();
  }

  @Test
  void registersTheThreePipelineMeters() {
    binder.bindTo(registry);

    assertThat(registry.getMeters())
        .extracting(meter -> meter.getId().getName())
        .containsExactlyInAnyOrder(
            "ntfy.pipeline.published", "ntfy.pipeline.suppressed", "ntfy.pipeline.failed");
    assertThat(registry.get("ntfy.pipeline.failed").functionCounter().getId().getDescription())
        .isNotBlank();
  }

  /**
   * No handler installed — an inactive config, or simply a scrape that lands before logging is set
   * up — must read as {@code 0}, not blow up and not make the meters disappear.
   */
  @Test
  void noHandlerInstalled_metersReadZero() {
    binder.bindTo(registry);

    assertThat(count("ntfy.pipeline.published")).isZero();
    assertThat(count("ntfy.pipeline.suppressed")).isZero();
    assertThat(count("ntfy.pipeline.failed")).isZero();
  }

  /**
   * The end the binding exists for: a real publish through the installed handler's engine shows up
   * on the meter. Note the binder is bound BEFORE the handler is installed — the reverse of the
   * boot order — to prove the lazy read has no ordering dependency in either direction.
   */
  @Test
  void metersTrackTheInstalledHandlersCounters() throws InterruptedException {
    binder.bindTo(registry);
    assertThat(count("ntfy.pipeline.published")).isZero();

    NtfyJulHandler handler = installHandler();
    try {
      handler.publish(severeRecord("counted"));
      assertThat(server.waitForRequest()).isTrue();

      assertThat(count("ntfy.pipeline.published")).isEqualTo(1d);
      assertThat(count("ntfy.pipeline.suppressed")).isZero();
      assertThat(count("ntfy.pipeline.failed")).isZero();
    } finally {
      handler.close();
    }
  }

  /**
   * A re-install (a dev-mode reload) hands the holder a NEW handler's counters. The already-bound
   * meters must re-point at them without being rebound — that is the whole reason the read goes
   * through the holder on every scrape rather than capturing a counters instance at bind time.
   *
   * <p>The new counters restart from zero, and so does the exported value: {@code
   * CumulativeFunctionCounter} reports whatever its source function currently returns and does not
   * clamp a decrease. That is a counter reset, which is exactly what a monitoring system sees when
   * the process itself restarts, and it handles it the same way.
   */
  @Test
  void reinstallRepointsTheMetersAtTheNewCounters() throws InterruptedException {
    binder.bindTo(registry);

    NtfyJulHandler first = installHandler();
    try {
      first.publish(severeRecord("first-boot"));
      assertThat(server.waitForRequest()).isTrue();
      assertThat(count("ntfy.pipeline.published")).isEqualTo(1d);
    } finally {
      first.close();
    }

    NtfyJulHandler second = installHandler();
    try {
      assertThat(second.counters()).isNotSameAs(first.counters());
      assertThat(count("ntfy.pipeline.published"))
          .as("the meter now reads the new, still-empty counters")
          .isZero();

      second.publish(severeRecord("second-boot"));
      assertThat(server.waitForRequest()).isTrue();
      assertThat(count("ntfy.pipeline.published"))
          .as("and follows them from there, with no rebind")
          .isEqualTo(1d);
    } finally {
      second.close();
    }
  }

  /** An uninstall (context shutdown) drops the meters back to 0 rather than failing a scrape. */
  @Test
  void uninstallLeavesTheMetersReadingZero() throws InterruptedException {
    binder.bindTo(registry);

    NtfyJulHandler handler = installHandler();
    try {
      handler.publish(severeRecord("before-shutdown"));
      assertThat(server.waitForRequest()).isTrue();
      assertThat(count("ntfy.pipeline.published")).isEqualTo(1d);
    } finally {
      handler.close();
    }
    NtfyPipelineCountersHolder.set(null);

    assertThat(count("ntfy.pipeline.published")).isZero();
    assertThat(count("ntfy.pipeline.failed")).isZero();
  }

  /** Builds a handler against the loopback server and publishes its counters, as the recorder does. */
  private NtfyJulHandler installHandler() {
    NtfyConfig cfg =
        NtfyConfig.builder().url(server.baseUrl()).topic("alerts").maxAlertsPerWindow(10).build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    NtfyPipelineCountersHolder.set(handler.counters());
    return handler;
  }

  private static LogRecord severeRecord(String message) {
    LogRecord record = new LogRecord(Level.SEVERE, message);
    // A non-self logger name: the engine always excludes io.github.pimak.ntfy.*.
    record.setLoggerName("com.example.Boom");
    return record;
  }

  private double count(String meterName) {
    return registry.get(meterName).functionCounter().count();
  }

  /** Minimal loopback stand-in for an ntfy server, accepting every publish. */
  private static final class LoopbackNtfyServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

    LoopbackNtfyServer() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      server.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            // Recorded last: the engine increments `published` after the response is read, so the
            // caller polls the counter itself rather than treating arrival as completion.
            receivedPaths.add(exchange.getRequestURI().getPath());
          });
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Polls up to ~5s for a request to have arrived AND the pipeline to have counted it. */
    boolean waitForRequest() throws InterruptedException {
      for (int i = 0; i < 50; i++) {
        PipelineCounters counters = NtfyPipelineCountersHolder.get();
        if (!receivedPaths.isEmpty() && counters != null && counters.published() > 0) {
          return true;
        }
        TimeUnit.MILLISECONDS.sleep(100);
      }
      return false;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
