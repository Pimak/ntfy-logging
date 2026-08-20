package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the JUL handler inherits ntfy-core's storm behaviour end to end: a burst above the
 * allowance publishes exactly the allowed number of individual alerts and then exactly one
 * aggregated digest at window close, carrying the digest priority/tags and the exact suppressed
 * count — count-never-lost is the engine's promise, and this is the module that the Quarkus
 * extension installs. The JUL counterpart of {@code LogbackAlertAppenderDigestIT}, asserted against
 * the module's own loopback server because ntfy-jul's dependency allowlist admits no WireMock.
 */
class NtfyJulHandlerDigestIT {

  private static final int MAX_ALERTS_PER_WINDOW = 3;
  private static final int TOTAL_RECORDS = 10;
  private static final int EXPECTED_SUPPRESSED = TOTAL_RECORDS - MAX_ALERTS_PER_WINDOW; // 7
  private static final Duration SUPPRESSION_WINDOW = Duration.ofSeconds(2);
  private static final String LOGGER_NAME = "com.example.testapp.SimulatedConsumer";

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
  void burstAboveAllowance_producesExactlyOneDigestCarryingTheSuppressedCount()
      throws InterruptedException {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url(server.baseUrl())
            .topic("alerts")
            .maxAlertsPerWindow(MAX_ALERTS_PER_WINDOW)
            // 2 s — the same window LogbackAlertAppenderDigestIT picks, and safe from both
            // directions on a loaded CI box. Too short would be the real hazard: the window also
            // refills the burst allowance, so if the burst outlived it a 4th individual alert would
            // leak and the digest count would come up short. The burst below is 10 synchronous
            // loopback round-trips (single-digit milliseconds even under contention), leaving two
            // orders of magnitude of headroom. In the other direction the first digest tick is a
            // full window away, comfortably inside waitForRequests()' ~10 s poll budget.
            .suppressionWindow(SUPPRESSION_WINDOW)
            // Inline synchronous delivery: with the async worker in the way, "publish() returned"
            // and "the alert reached the wire" are separate events, so a digest tick could race
            // still-queued alerts and split the count across two notifications. The async path has
            // its own coverage in NtfyJulHandlerTest.
            .asyncEnabled(false)
            .build();

    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      for (int i = 0; i < TOTAL_RECORDS; i++) {
        LogRecord record = new LogRecord(Level.SEVERE, "boom " + i);
        record.setLoggerName(LOGGER_NAME);
        handler.publish(record);
      }

      // Waiting for the 4th request BEFORE close() is what makes this test worth having: close()
      // flushes any pending digest synchronously, so a version that asserted only afterwards would
      // still go green with a digest timer that never ticked at all. While the handler is open,
      // nothing but the scheduled tick can produce a 4th request.
      assertThat(server.waitForRequests(MAX_ALERTS_PER_WINDOW + 1)).isTrue();
    } finally {
      handler.close();
    }

    // Exactly four requests, all on the configured topic. A second digest, or a 4th individual
    // alert leaked by an allowance rollover, surfaces here as a size mismatch.
    assertThat(server.receivedPaths()).hasSize(MAX_ALERTS_PER_WINDOW + 1).containsOnly("/alerts");

    // The allowance must be spent on the EARLIEST records, not an arbitrary three of the ten: a
    // limiter that gated on the wrong side of its counter would still publish three alerts, just
    // not these.
    List<String> bodies = server.receivedBodies();
    for (int i = 0; i < MAX_ALERTS_PER_WINDOW; i++) {
      assertThat(bodies.get(i)).contains("boom " + i);
    }

    // Every one of the seven rejected records is accounted for in the single digest, and the window
    // it names is the one actually scheduled. An off-by-one in drainAndReset(), a digest that only
    // reported the newest window's overflow, or a per-logger tally that lost events, all fail here.
    String digestBody = bodies.get(MAX_ALERTS_PER_WINDOW);
    assertThat(digestBody)
        .contains(EXPECTED_SUPPRESSED + " errors suppressed in the last 2 seconds")
        .contains(EXPECTED_SUPPRESSED + "x " + LOGGER_NAME);

    // Style is how a phone tells a digest apart from an alert, and it is route state rather than
    // payload text — a swap of the two header pairs would be invisible to the body assertions
    // above.
    for (int i = 0; i < MAX_ALERTS_PER_WINDOW; i++) {
      Headers alertHeaders = server.receivedHeaders().get(i);
      assertThat(alertHeaders.getFirst("Priority")).isEqualTo("high");
      assertThat(alertHeaders.getFirst("Tags")).isEqualTo("rotating_light");
    }
    Headers digestHeaders = server.receivedHeaders().get(MAX_ALERTS_PER_WINDOW);
    assertThat(digestHeaders.getFirst("Priority")).isEqualTo("urgent");
    assertThat(digestHeaders.getFirst("Tags")).isEqualTo("fire");

    // The counters the Quarkus extension's Micrometer binding reads must tell the same story as the
    // wire: seven gated, four notifications delivered (three alerts + the digest), nothing failed.
    assertThat(handler.counters().suppressed()).isEqualTo(EXPECTED_SUPPRESSED);
    assertThat(handler.counters().published()).isEqualTo(MAX_ALERTS_PER_WINDOW + 1);
    assertThat(handler.counters().failed()).isZero();
  }
}
