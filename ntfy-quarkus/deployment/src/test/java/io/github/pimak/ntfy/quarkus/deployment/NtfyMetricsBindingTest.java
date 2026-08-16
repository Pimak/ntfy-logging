package io.github.pimak.ntfy.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Boots a real (in-JVM) Quarkus application with both the ntfy extension and {@code
 * quarkus-micrometer} active, and asserts the whole metrics chain end-to-end: the capability-gated
 * build step registers {@code NtfyMeterBinder}, the Micrometer extension discovers it as a {@code
 * MeterBinder} bean and applies it to the registry, and the meters read the counters of the log
 * handler that the RUNTIME_INIT recorder installed — a handler this test never touches directly.
 *
 * <p>The application supplies its own {@link SimpleMeterRegistry} because the test declares no
 * exporter extension (Prometheus, OTLP, …): without a child registry the root composite has nothing
 * to delegate to and every meter would read {@code 0} whatever the counters said. Quarkus's own root
 * registry producer is an {@code @Alternative} with a priority, so it still wins the injection point
 * below — {@code registry} is the composite, and the {@code SimpleMeterRegistry} sits underneath it.
 *
 * <p>The counterpart negative case — Micrometer absent — is covered by {@link
 * NtfyProcessorMetricsGateTest}, which drives the build step directly. It cannot be a {@code
 * QuarkusUnitTest}: every test in this module shares one classpath, and this one has to put
 * Micrometer on it.
 */
class NtfyMetricsBindingTest {

  // Loopback ntfy stand-in, started before Quarkus boots so its port can be fed into config. It
  // only has to answer 200 — this test reads the outcome off the meters, not off the server.
  static final HttpServer SERVER = startServer();

  private static HttpServer startServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
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

  /**
   * Releases the server's listening socket and dispatcher thread. Surefire runs every test class of
   * this module in one JVM, so a server left running would hold both for the rest of the run.
   */
  @AfterAll
  static void stopServer() {
    SERVER.stop(0);
  }

  @RegisterExtension
  static final QuarkusUnitTest TEST =
      new QuarkusUnitTest()
          .withApplicationRoot(jar -> jar.addClass(TestRegistryProducer.class))
          .overrideConfigKey(
              "quarkus.ntfy.url", "http://127.0.0.1:" + SERVER.getAddress().getPort())
          .overrideConfigKey("quarkus.ntfy.topic", "alerts")
          .overrideConfigKey("quarkus.ntfy.max-alerts-per-window", "10");

  @Inject MeterRegistry registry;

  @Test
  void pipelineMetersAreRegisteredAndTrackTheInstalledHandler() throws InterruptedException {
    assertThat(registry.find("ntfy.pipeline.published").functionCounter())
        .as("the ntfy MeterBinder should have been discovered and applied")
        .isNotNull();
    assertThat(registry.find("ntfy.pipeline.suppressed").functionCounter()).isNotNull();
    assertThat(registry.find("ntfy.pipeline.failed").functionCounter()).isNotNull();

    // A non-self logger name (the engine always excludes io.github.pimak.ntfy.*).
    Logger.getLogger("com.example.alert")
        .log(Level.SEVERE, "boom", new IllegalStateException("bad"));

    assertThat(waitForPublishedMeter())
        .as("the published meter should follow the installed handler's counters")
        .isEqualTo(1d);
    assertThat(registry.find("ntfy.pipeline.failed").functionCounter().count()).isZero();
  }

  /** Polls up to ~5s for the loopback server to answer AND the meter to reflect the publish. */
  private double waitForPublishedMeter() throws InterruptedException {
    double count = 0d;
    for (int i = 0; i < 50 && count == 0d; i++) {
      TimeUnit.MILLISECONDS.sleep(100);
      count = registry.find("ntfy.pipeline.published").functionCounter().count();
    }
    return count;
  }

  /**
   * Gives the composite root registry something to delegate to. Quarkus collects {@code
   * MeterRegistry} beans and adds them as children of the root composite.
   */
  @Singleton
  public static class TestRegistryProducer {

    @Produces
    @Singleton
    public MeterRegistry simpleRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
