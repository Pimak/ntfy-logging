package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.NtfyAction;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code actions} config reaches the wire as ntfy's {@code Actions} HTTP header on the
 * JUL path: forwarded verbatim when set as a raw short-format string, serialized from typed {@link
 * NtfyAction}s when set that way, and entirely absent when left unset. The JUL counterpart of
 * {@code LogbackAlertAppenderActionsHeaderIT}, asserted against the module's own loopback server
 * because ntfy-jul's dependency allowlist admits no WireMock.
 */
class NtfyJulHandlerActionsHeaderIT {

  private static final String ACTIONS_HEADER = "view, View logs, https://grafana.example.com/d/abc";

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

  private void publishOneAlert(NtfyConfig cfg) throws InterruptedException {
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    try {
      handler.publish(severeRecord());

      assertThat(server.waitForRequest()).isTrue();
    } finally {
      handler.close();
    }
    assertThat(server.receivedHeaders()).hasSize(1);
  }

  @Test
  void severeRecord_withRawActionsConfigured_sendsActionsHeaderVerbatim()
      throws InterruptedException {
    publishOneAlert(baseConfig().actionsHeader(ACTIONS_HEADER).build());

    // Verbatim, not merely "contains": ntfy parses this header positionally, so a value that
    // survived with re-ordered or re-quoted segments would yield different buttons.
    assertThat(server.receivedHeaders().get(0).getFirst("Actions")).isEqualTo(ACTIONS_HEADER);
  }

  @Test
  void severeRecord_withTypedActionsConfigured_sendsTheSerializedActionsHeader()
      throws InterruptedException {
    publishOneAlert(
        baseConfig()
            .actions(List.of(NtfyAction.view("View logs", "https://grafana.example.com/d/abc")))
            .build());

    // The typed builder is the API the Quarkus extension and hand-wired JUL setups reach for, so
    // the NtfyAction -> serializer -> header hop needs proving on the wire, not just in the
    // serializer's own unit tests: a config that stored the typed list but never serialized it
    // would send no header at all and pass a mere non-null check.
    assertThat(server.receivedHeaders().get(0).getFirst("Actions")).isEqualTo(ACTIONS_HEADER);
  }

  @Test
  void severeRecord_withoutActions_sendsNoActionsHeader() throws InterruptedException {
    publishOneAlert(baseConfig().build());

    // Absent, not empty. ntfy rejects a malformed Actions value with HTTP 400, so an unconditional
    // header() call would turn every alert into a failed publish rather than a cosmetic defect.
    assertThat(server.receivedHeaders().get(0).containsKey("Actions")).isFalse();
  }
}
