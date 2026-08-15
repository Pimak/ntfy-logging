package com.example.ntfymicronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

/**
 * Example Micronaut application with ntfy error alerting: {@code ntfy-micronaut} binds the {@code
 * ntfy.*} properties from {@code application.yml}, installs the alert appender on the root Logback
 * logger once the context has started (every ERROR log becomes a push notification), and exposes an
 * injectable {@link io.github.pimak.ntfy.core.NtfyClient} for manual notifications — see {@link
 * DeploymentNotifier}.
 *
 * <p>Run it with {@code NTFY_URL} and {@code NTFY_TOPIC} set (the {@code application.yml}
 * placeholders read them, and {@code -Dntfy.url} / {@code -Dntfy.topic} override them) and both
 * notifications below arrive on the topic.
 *
 * <p>The context is closed in a try-with-resources rather than left to the JVM shutdown hook only
 * because this application has no server keeping it alive: closing it is what makes the container
 * run the module's {@code @PreDestroy}, which stops the appender — draining queued async alerts and
 * flushing the pending storm digest — and closes the {@link io.github.pimak.ntfy.core.NtfyClient}.
 * A long-running Micronaut service gets the same treatment from the shutdown hook Micronaut
 * registers. This module's integration tests boot this very configuration against a loopback ntfy
 * stand-in.
 */
public final class NtfyMicronautExampleApplication {

  private NtfyMicronautExampleApplication() {}

  public static void main(String[] args) {
    try (ApplicationContext context =
        Micronaut.run(NtfyMicronautExampleApplication.class, args)) {
      // Automatic path: an ordinary ERROR log inside the service becomes an alert.
      context.getBean(OrderService.class).placeOrder("4711");
      // Manual path: an explicit notification through the injected client.
      context.getBean(DeploymentNotifier.class).announce("1.4.2");
    }
  }
}
