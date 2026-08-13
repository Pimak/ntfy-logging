package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end programmatic install: system properties configure ntfy, {@link NtfyJulInstaller}
 * attaches the handler, and a SEVERE log through a real JUL logger reaches the loopback server.
 * Config is injected via {@code ntfy.*} system properties (the highest-precedence {@code
 * ConfigLoader} layer), restored after each test.
 */
class NtfyJulInstallerTest {

  // Strong reference: JUL holds loggers weakly, so the test must pin its logger itself.
  private final Logger logger = Logger.getLogger("test.ntfy.installer");

  private LoopbackNtfyServer server;

  @BeforeEach
  void startServer() {
    server = new LoopbackNtfyServer();
  }

  @AfterEach
  void cleanUp() {
    System.clearProperty("ntfy.url");
    System.clearProperty("ntfy.topic");
    if (server != null) {
      server.close();
    }
  }

  @Test
  void installsFromSystemPropertiesAndAlertsOnSevere() throws InterruptedException {
    System.setProperty("ntfy.url", server.baseUrl());
    System.setProperty("ntfy.topic", "installer-alerts");

    Optional<NtfyJulHandler> handler = NtfyJulInstaller.install(logger);
    assertThat(handler).isPresent();
    try {
      logger.log(Level.SEVERE, "installer kaboom", new IllegalStateException("bad state"));

      assertThat(server.waitForRequest()).isTrue();
      assertThat(server.receivedPaths()).contains("/installer-alerts");
      assertThat(logger.getHandlers()).contains(handler.get());
    } finally {
      NtfyJulInstaller.uninstall(logger, handler.get());
    }
    assertThat(logger.getHandlers()).doesNotContain(handler.get());
  }

  @Test
  void unconfiguredEnvironmentInstallsNothing() {
    Optional<NtfyJulHandler> handler = NtfyJulInstaller.install(logger);

    assertThat(handler).isEmpty();
    assertThat(logger.getHandlers()).isEmpty();
  }
}
