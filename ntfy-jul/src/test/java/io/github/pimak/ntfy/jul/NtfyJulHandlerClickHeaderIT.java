package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.NtfyConfig;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code clickUrl} config reaches the wire as ntfy's {@code Click} HTTP header on the
 * JUL path: present and verbatim when configured, entirely absent when left unset (a blank/null
 * value must send no {@code Click} header rather than an empty one). The JUL counterpart of {@code
 * LogbackAlertAppenderClickHeaderIT}, asserted against the module's own loopback server because
 * ntfy-jul's dependency allowlist admits no WireMock.
 */
class NtfyJulHandlerClickHeaderIT {

  private static final String CLICK_URL = "https://grafana.example.com/d/abc123";

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

  private NtfyConfig.Builder baseConfig() {
    return NtfyConfig.builder()
        .url(server.baseUrl())
        .topic("alerts")
        // Well above the single record each test publishes, so nothing here can be rate-limited
        // into a digest and change which notification index 0 refers to.
        .maxAlertsPerWindow(10)
        // Inline synchronous delivery: exactly one request, no worker handoff between publish() and
        // the wire. The async path has its own coverage in NtfyJulHandlerTest.
        .asyncEnabled(false);
  }

  private static LogRecord severeRecord() {
    LogRecord record = new LogRecord(Level.SEVERE, "boom");
    record.setLoggerName("com.example.testapp.SimulatedConsumer");
    return record;
  }

  @Test
  void severeRecord_withClickUrlConfigured_sendsClickHeaderVerbatim() throws InterruptedException {
    NtfyJulHandler handler = NtfyJulHandler.forConfig(baseConfig().clickUrl(CLICK_URL).build());
    try {
      handler.publish(severeRecord());

      assertThat(server.waitForRequest()).isTrue();
    } finally {
      handler.close();
    }

    // Verbatim, not merely "contains": the URL is what the phone opens on tap, so any escaping,
    // trimming or re-encoding applied on the way to the header would break the deep link.
    assertThat(server.receivedHeaders()).hasSize(1);
    assertThat(server.receivedHeaders().get(0).getFirst("Click")).isEqualTo(CLICK_URL);
  }

  @Test
  void severeRecord_withoutClickUrl_sendsNoClickHeader() throws InterruptedException {
    NtfyJulHandler handler = NtfyJulHandler.forConfig(baseConfig().build());
    try {
      handler.publish(severeRecord());

      assertThat(server.waitForRequest()).isTrue();
    } finally {
      handler.close();
    }

    // Absent, not empty. An unconditional header() call would send `Click:` on every alert, which
    // ntfy renders as a tap target that goes nowhere — a defect no body assertion would notice.
    assertThat(server.receivedHeaders()).hasSize(1);
    assertThat(server.receivedHeaders().get(0).containsKey("Click")).isFalse();
  }
}
