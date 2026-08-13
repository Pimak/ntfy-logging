package com.example.ntfyspring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.github.pimak.ntfy.core.PublishResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Functional test of the example's two base paths, booting the real application: an ordinary ERROR
 * log from a service becomes a ntfy publish (starter-installed appender), and the injectable {@link
 * io.github.pimak.ntfy.core.NtfyClient} bean sends manual notifications to the same topic.
 */
class SpringExampleAlertIT {

  @Test
  void errorLogBecomesANtfyAlert() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        ConfigurableApplicationContext context = ExampleApplications.run(server)) {
      context.getBean(OrderService.class).placeOrder("4711");

      LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(request.path()).isEqualTo("/alerts");
      assertThat(request.header("Priority")).isEqualTo("high");
      assertThat(request.body())
          .contains("Order 4711 failed")
          .contains("com.example.ntfyspring.OrderService")
          .contains("IllegalStateException")
          .contains("payment gateway timeout for order 4711");
    }
  }

  @Test
  void injectedNtfyClientSendsManualNotifications() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        ConfigurableApplicationContext context = ExampleApplications.run(server)) {
      PublishResult result = context.getBean(DeploymentNotifier.class).announce("1.4.2");

      assertThat(result.success()).isTrue();
      LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(request.path()).isEqualTo("/alerts");
      assertThat(request.header("Title")).isEqualTo("Deployed 1.4.2");
      assertThat(request.body()).contains("The order service is now running 1.4.2");
    }
  }
}
