package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PipelineCounters;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full runtime path: an {@link NtfyJulHandler} built via {@link
 * NtfyJulHandler#forConfig} (so the factory's engine wiring is covered too), pointed at a loopback
 * server standing in for ntfy.
 */
class NtfyJulHandlerTest {

  private LoopbackNtfyServer server;

  @BeforeEach
  void startServer() {
    server = new LoopbackNtfyServer();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void severeRecordIsPublishedToNtfy() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder().url(server.baseUrl()).topic("alerts").maxAlertsPerWindow(10).build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "kaboom");
      record.setLoggerName("com.example.Boom");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void severeRecordIsPublishedInAsyncMode() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(server.baseUrl())
            .topic("alerts")
            .maxAlertsPerWindow(10)
            .asyncEnabled(true)
            .build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord record = new LogRecord(Level.SEVERE, "kaboom-async");
      record.setLoggerName("com.example.Boom");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      // The publish is offloaded to the ntfy-alert-delivery worker; it still reaches the server.
      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/alerts");
    } finally {
      handler.close();
    }
  }

  @Test
  void belowSevereRecordIsIgnored() throws InterruptedException {
    NtfyConfig cfg = NtfyConfig.builder().url(server.baseUrl()).topic("alerts").build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord record = new LogRecord(Level.WARNING, "just a warning");
      record.setLoggerName("com.example.Warn");
      handler.publish(record);

      // Give any (erroneous) async publish a moment; nothing should arrive.
      TimeUnit.MILLISECONDS.sleep(300);
      assertThat(server.receivedPaths()).isEmpty();
    } finally {
      handler.close();
    }
  }

  @Test
  void warningRecordIsRoutedToTheWarnTopicOnceOneIsConfigured() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(server.baseUrl())
            .topic("alerts")
            .warnTopic("alerts-warn")
            .maxAlertsPerWindow(10)
            .build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      LogRecord warning = new LogRecord(Level.WARNING, "just a warning");
      warning.setLoggerName("com.example.Warn");
      handler.publish(warning);

      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/alerts-warn");
    } finally {
      handler.close();
    }
  }

  @Test
  void configuringAWarnTopicNeverOpensTheGateBelowWarning() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(server.baseUrl())
            .topic("alerts")
            .warnTopic("alerts-warn")
            .build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      // JUL's INFO/CONFIG/FINE sit below WARNING and must stay unpublished on every configuration.
      for (Level level : new Level[] {Level.INFO, Level.CONFIG, Level.FINE}) {
        LogRecord record = new LogRecord(level, "routine line");
        record.setLoggerName("com.example.Routine");
        handler.publish(record);
      }

      TimeUnit.MILLISECONDS.sleep(300);
      assertThat(server.receivedPaths()).isEmpty();
    } finally {
      handler.close();
    }
  }

  /**
   * {@link NtfyJulHandler#counters()} is the JUL counterpart of the appenders' {@code
   * getCounters()}, and the route the Quarkus extension's Micrometer binding takes to the pipeline
   * tallies. It must expose the engine's live instance — the same object across reads — and reflect
   * a successful publish.
   */
  @Test
  void countersExposeThePipelineTallies() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder().url(server.baseUrl()).topic("alerts").maxAlertsPerWindow(10).build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      assertThat(handler.counters()).isNotNull().isSameAs(handler.counters());
      assertThat(handler.counters().snapshot()).isEqualTo(new PipelineCounters.Snapshot(0, 0, 0));

      LogRecord record = new LogRecord(Level.SEVERE, "counted");
      record.setLoggerName("com.example.Counted");
      handler.publish(record);

      assertThat(server.waitForRequest()).isTrue();
      assertThat(awaitPublished(handler, 1)).isTrue();
      assertThat(handler.counters().published()).isEqualTo(1);
      assertThat(handler.counters().suppressed()).isZero();
      assertThat(handler.counters().failed()).isZero();
    } finally {
      handler.close();
    }
  }

  /**
   * The pre-{@code include-mdc-keys} constructor is kept for source/binary compatibility: it must
   * still publish, and must render no MDC context even from a record that carries one.
   */
  @Test
  void singleArgConstructor_stillWorks_andRendersNoMdc() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder().url(server.baseUrl()).topic("alerts").maxAlertsPerWindow(10).build();
    AlertEngine engine = new AlertEngine(cfg, new JulDiagnostics());
    engine.start();
    NtfyJulHandler handler = new NtfyJulHandler(engine);
    try {
      ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "legacy-ctor");
      record.setLoggerName("com.example.Legacy");
      record.withMdc("requestId", "req-legacy");

      handler.publish(record);

      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedBodies()).hasSize(1);
      assertThat(server.receivedBodies().get(0)).contains("legacy-ctor").doesNotContain("req-");
      // Not even consulted: an empty allow-list short-circuits before any MDC access.
      assertThat(record.mdcCallCount()).isZero();
    } finally {
      handler.close();
    }
  }

  /**
   * End-to-end: an allow-listed MDC key configured on {@link NtfyConfig} reaches the published body
   * as its own {@code key: value} line — while a key that was NOT allow-listed does not, value and
   * all. The record is an {@code org.jboss.logmanager.ExtLogRecord} stub, so the real reflective
   * detection path runs (see that class's header comment).
   */
  @Test
  void allowListedMdcValueReachesThePublishedBody() throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(server.baseUrl())
            .topic("alerts")
            .maxAlertsPerWindow(10)
            .includeMdcKeys(List.of("requestId"))
            .build();
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "kaboom-mdc");
      record.setLoggerName("com.example.Boom");
      record.withMdc("requestId", "req-4711").withMdc("sessionToken", "super-secret");
      record.setThrown(new IllegalStateException("bad state"));

      handler.publish(record);

      assertThat(server.waitForRequest()).isTrue();
      String body = server.receivedBodies().get(0);
      assertThat(body).contains("requestId: req-4711");
      assertThat(body).doesNotContain("sessionToken").doesNotContain("super-secret");
    } finally {
      handler.close();
    }
  }

  /**
   * Polls up to ~5s for the engine to have counted at least {@code expected} publishes, mirroring
   * {@link LoopbackNtfyServer#waitForRequest}. "A request arrived" is the strictly earlier event:
   * the loopback handler records the path before it writes the response, while the engine counts a
   * publish only once the client has that response back. Reading the tally the moment
   * waitForRequest() returns is therefore a race, and lost it once in five local runs.
   */
  private static boolean awaitPublished(NtfyJulHandler handler, long expected)
      throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (handler.counters().published() >= expected) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return handler.counters().published() >= expected;
  }
}
