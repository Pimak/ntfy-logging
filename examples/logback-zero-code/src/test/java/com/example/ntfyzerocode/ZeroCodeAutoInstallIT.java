package com.example.ntfyzerocode;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The JVM control for {@link ZeroCodeNativeBinaryIT}: the same probe, the same assertions, on an
 * ordinary JVM. Its job is to make the native leg readable — when a native binary fails, the first
 * question is always whether the probe itself is sound, and a green control answers it without a
 * second investigation.
 *
 * <p>It is also the only application-level test of the zero-code path in this repository:
 * {@code ntfy-logback}'s own tests drive {@code NtfyLogbackConfigurator} directly, which proves the
 * class works but not that Logback's service lookup finds it.
 *
 * <p><strong>One shot per JVM.</strong> The configuration has to be visible before the first SLF4J
 * call, because that call is what triggers Logback's default configuration — so the properties are
 * set here rather than in the pom (the loopback port is only known at run time), and this class is
 * deliberately the module's only test that touches SLF4J. A second one would find the context
 * already configured and prove nothing.
 */
class ZeroCodeAutoInstallIT {

  @Test
  void theConfiguratorSpiInstallsTheAppenderAndItPublishes() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      // System properties rather than environment: a test cannot set the latter, and ConfigLoader
      // ranks them above it anyway. The native leg uses the environment, which is the path a
      // zero-code deployment actually takes.
      System.setProperty("ntfy.url", server.url());
      System.setProperty("ntfy.topic", "alerts");
      // Synchronous: the publish is done when the logging call returns, so no polling is needed and
      // a missing request is a failure rather than a race.
      System.setProperty("ntfy.async", "false");
      try {
        assertThat(ZeroCodeNativeProbe.run())
            .as("the probe reports its own verdict; 4 means the SPI installed nothing")
            .isZero();

        LoopbackNtfyServer.ReceivedRequest alert = server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(alert.path()).isEqualTo("/alerts");
        assertThat(alert.body()).contains("row 42: malformed record");
        assertThat(server.received())
            .as("no logback.xml here, so exactly one appender can publish")
            .hasSize(1);
      } finally {
        System.clearProperty("ntfy.url");
        System.clearProperty("ntfy.topic");
        System.clearProperty("ntfy.async");
      }
    }
  }
}
