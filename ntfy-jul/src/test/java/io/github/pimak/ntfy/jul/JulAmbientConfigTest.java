package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.Diagnostics;
import io.github.pimak.ntfy.core.NtfyConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The supply-chain guard on the zero-code paths: a classpath-only endpoint is refused without the
 * explicit opt-in. {@link JulAmbientConfig#vet} is tested directly against hand-built configs, so no
 * real environment state is involved.
 */
class JulAmbientConfigTest {

  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @Test
  void inactiveConfigIsSilentlyEmpty() {
    NtfyConfig cfg = NtfyConfig.builder().build();

    assertThat(JulAmbientConfig.vet(cfg, diagnostics)).isEmpty();
    assertThat(diagnostics.warnings).isEmpty();
  }

  @Test
  void activeEnvironmentConfigPassesWithoutWarnings() {
    NtfyConfig cfg = NtfyConfig.builder().url("https://ntfy.example.com").topic("alerts").build();

    assertThat(JulAmbientConfig.vet(cfg, diagnostics)).contains(cfg);
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

    assertThat(JulAmbientConfig.vet(cfg, diagnostics)).isEmpty();
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

    assertThat(JulAmbientConfig.vet(cfg, diagnostics)).contains(cfg);
    assertThat(diagnostics.warnings).hasSize(1);
    assertThat(diagnostics.warnings.get(0)).contains("make sure that file is one you trust");
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
