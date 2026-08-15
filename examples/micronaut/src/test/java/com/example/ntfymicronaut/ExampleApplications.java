package com.example.ntfymicronaut;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.micronaut.context.ApplicationContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boots a real Micronaut {@link ApplicationContext} for a test, on the example's own {@code
 * application.yml} — {@code src/main/resources} is on the test classpath, so the shipped
 * configuration is loaded exactly as it would be in production and cannot drift from what the tests
 * prove.
 *
 * <p>The properties handed to {@link ApplicationContext#run(Map, String...)} form a property source
 * that outranks the configuration file, which is how the endpoint is pointed at the loopback ntfy
 * stand-in; {@code overrides} flip per-test knobs ({@code ntfy.async}, storm-suppression settings,
 * ...) the way an operator would override them from the environment. Everything not overridden —
 * notably {@code ntfy.app-name} — still comes from {@code application.yml}.
 */
final class ExampleApplications {

  private ExampleApplications() {}

  static ApplicationContext run(LoopbackNtfyServer server) {
    return run(server, Map.of());
  }

  static ApplicationContext run(LoopbackNtfyServer server, Map<String, Object> overrides) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("ntfy.url", server.url());
    properties.put("ntfy.topic", "alerts");
    properties.putAll(overrides);
    // run(..) starts the context, which publishes StartupEvent — the point at which the module
    // installs the appender on the root Logback logger. Returned started, and AutoCloseable.
    return ApplicationContext.run(properties);
  }
}
