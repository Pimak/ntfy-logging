package com.example.ntfycore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.github.pimak.ntfy.core.PublishResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Functional test of the example's {@link io.github.pimak.ntfy.core.NtfyClient} usage: the manual
 * notification published by {@link CoreExampleApp#sendManualNotification} lands on the configured
 * topic with its title and body intact.
 */
class CoreClientExampleIT {

  @Test
  void manualNotificationLandsOnTheConfiguredTopic() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      PublishResult result =
          CoreExampleApp.sendManualNotification(CoreExampleApp.baseConfig(server.url(), "alerts"));

      assertThat(result.success()).isTrue();

      LoopbackNtfyServer.ReceivedRequest request =
          server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(request.path()).isEqualTo("/alerts");
      assertThat(request.header("Title")).isEqualTo("Batch complete");
      assertThat(request.body()).contains("12,304 rows processed");
    }
  }
}
