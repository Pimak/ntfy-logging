package com.example.ntfyspring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Example Spring Boot application with ntfy error alerting: the {@code ntfy-spring-boot-starter}
 * auto-configuration reads the {@code ntfy.*} properties from {@code application.yml}, installs the
 * alert appender on the root Logback logger (every ERROR log becomes a push notification), and
 * exposes an injectable {@link io.github.pimak.ntfy.core.NtfyClient} for manual notifications —
 * see {@link DeploymentNotifier}.
 *
 * <p>On a clean context shutdown the starter stops the appender, which drains any queued async
 * alerts and flushes the pending storm digest. This module's integration tests boot this very
 * application against a loopback ntfy stand-in.
 */
@SpringBootApplication
public class NtfySpringExampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(NtfySpringExampleApplication.class, args);
  }
}
