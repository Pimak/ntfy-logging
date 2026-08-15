package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pimak.ntfy.core.Diagnostics;
import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * The supply-chain guard on the programmatic install path: a classpath-only endpoint is refused
 * without the explicit opt-in. {@link Log4j2AmbientConfig#vet} is tested directly against
 * hand-built configs, so no real environment state and no {@code LoggerContext} is involved.
 */
class Log4j2AmbientConfigTest {

  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @Test
  void inactiveConfigIsSilentlyEmpty() {
    NtfyConfig cfg = NtfyConfig.builder().build();

    assertThat(Log4j2AmbientConfig.vet(cfg, diagnostics)).isEmpty();
    assertThat(diagnostics.warnings).isEmpty();
  }

  @Test
  void activeEnvironmentConfigPassesWithoutWarnings() {
    NtfyConfig cfg = NtfyConfig.builder().url("https://ntfy.example.com").topic("alerts").build();

    assertThat(Log4j2AmbientConfig.vet(cfg, diagnostics)).contains(cfg);
    assertThat(diagnostics.warnings).isEmpty();
  }

  @Test
  void classpathOnlyEndpointIsRefusedWithoutOptIn() {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url("https://user:secret@evil.example.com")
            .topic("alerts")
            .endpointFromClasspathFile(true)
            .build();

    assertThat(Log4j2AmbientConfig.vet(cfg, diagnostics)).isEmpty();
    assertThat(diagnostics.warnings).hasSize(1);
    assertThat(diagnostics.warnings.get(0))
        .contains("refusing")
        .contains("https://evil.example.com")
        // Embedded credentials must never be echoed into the diagnostic.
        .doesNotContain("secret");
  }

  @Test
  void classpathEndpointWithOptInPassesButWarnsLoudly() {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url("https://ntfy.example.com")
            .topic("alerts")
            .endpointFromClasspathFile(true)
            .allowClasspathEndpoint(true)
            .build();

    assertThat(Log4j2AmbientConfig.vet(cfg, diagnostics)).contains(cfg);
    assertThat(diagnostics.warnings).hasSize(1);
    assertThat(diagnostics.warnings.get(0)).contains("make sure that file is one you trust");
  }

  @Test
  void optedInClasspathEndpointWithUserinfoIsStrippedInTheWarning() {
    NtfyConfig cfg =
        NtfyConfig.builder()
            .url("https://user:secret@ntfy.example.com")
            .topic("alerts")
            .endpointFromClasspathFile(true)
            .allowClasspathEndpoint(true)
            .build();

    assertThat(Log4j2AmbientConfig.vet(cfg, diagnostics)).contains(cfg);
    assertThat(diagnostics.warnings.get(0))
        .contains("https://ntfy.example.com")
        .doesNotContain("secret");
  }

  private static final class RecordingDiagnostics implements Diagnostics {
    final List<String> warnings = new ArrayList<>();

    @Override
    public void info(String message) {}

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void error(String message, Throwable throwable) {}
  }
}
