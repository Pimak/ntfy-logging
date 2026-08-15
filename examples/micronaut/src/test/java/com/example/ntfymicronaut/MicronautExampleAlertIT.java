package com.example.ntfymicronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.github.pimak.ntfy.core.PublishResult;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Functional test of the example's two base paths, booting the real application context: an
 * ordinary ERROR log from a singleton becomes a ntfy publish (the appender the module installs on
 * the root logger at startup), and the injectable {@link io.github.pimak.ntfy.core.NtfyClient} bean
 * sends manual notifications to the same topic.
 */
class MicronautExampleAlertIT {

  @Test
  void errorLogBecomesANtfyAlert() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        ApplicationContext context = ExampleApplications.run(server)) {
      context.getBean(OrderService.class).placeOrder("4711");

      LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(request.path()).isEqualTo("/alerts");
      assertThat(request.header("Priority")).isEqualTo("high");
      assertThat(request.body())
          .contains("Order 4711 failed")
          .contains("com.example.ntfymicronaut.OrderService")
          .contains("IllegalStateException")
          .contains("payment gateway timeout for order 4711");
      // Only the endpoint is overridden for the test, so app-name can only have come from the
      // shipped application.yml: this is what proves the example's own configuration file was
      // loaded and bound, rather than the test quietly configuring the engine by itself. Prefix
      // match because the engine appends the root cause's class name to the title.
      assertThat(request.header("Title")).startsWith("order-service");
    }
  }

  @Test
  void injectedNtfyClientSendsManualNotifications() throws InterruptedException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer();
        ApplicationContext context = ExampleApplications.run(server)) {
      PublishResult result = context.getBean(DeploymentNotifier.class).announce("1.4.2");

      assertThat(result.success()).isTrue();
      LoopbackNtfyServer.ReceivedRequest request = server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(request.path()).isEqualTo("/alerts");
      assertThat(request.header("Title")).isEqualTo("Deployed 1.4.2");
      assertThat(request.body()).contains("The order service is now running 1.4.2");
    }
  }
}
