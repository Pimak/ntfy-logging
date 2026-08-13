package com.example.ntfyspring;

import com.example.ntfytestkit.LoopbackNtfyServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real {@link NtfySpringExampleApplication} for a test. Command-line property arguments
 * (highest precedence) point the example's {@code application.yml} endpoint at the loopback ntfy
 * stand-in; {@code extraArgs} flip per-test knobs ({@code --ntfy.async=true}, storm-suppression
 * settings, ...) exactly the way an operator would override them.
 */
final class ExampleApplications {

  private ExampleApplications() {}

  static ConfigurableApplicationContext run(LoopbackNtfyServer server, String... extraArgs) {
    List<String> args = new ArrayList<>();
    args.add("--spring.main.banner-mode=off");
    args.add("--ntfy.url=" + server.url());
    args.add("--ntfy.topic=alerts");
    Collections.addAll(args, extraArgs);
    return SpringApplication.run(NtfySpringExampleApplication.class, args.toArray(String[]::new));
  }
}
