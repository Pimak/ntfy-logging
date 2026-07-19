package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link AlertEngine} gating that used to live on the Logback appender
 * ({@code isExcluded}/{@code hasNoAlertMarker}) and now belongs entirely to the framework-neutral
 * engine. No HTTP, no lifecycle — the gates are pure functions of the config and the event.
 */
class AlertEngineGateTest {

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static AlertEngine engineWithExclusions(List<String> prefixes) {
    NtfyConfig config =
        NtfyConfig.builder()
            .url("https://ntfy.example.com")
            .topic("t")
            .excludedLoggerPrefixes(prefixes)
            .build();
    return new AlertEngine(config, NO_OP);
  }

  @Test
  void selfPackageIsAlwaysExcludedEvenWithNoConfiguredPrefixes() {
    AlertEngine engine = engineWithExclusions(List.of());
    assertThat(engine.isExcluded("io.github.pimak.ntfy")).isTrue();
    assertThat(engine.isExcluded("io.github.pimak.ntfy.core.AlertEngine")).isTrue();
    assertThat(engine.isExcluded("io.github.pimak.ntfy.logback.LogbackAlertAppender")).isTrue();
  }

  @Test
  void nonExcludedLoggerPasses() {
    AlertEngine engine = engineWithExclusions(List.of());
    assertThat(engine.isExcluded("com.acme.service.OrderService")).isFalse();
  }

  @Test
  void configuredPrefixExcludesExactMatchAndDescendants() {
    AlertEngine engine = engineWithExclusions(List.of("org.apache.kafka"));
    assertThat(engine.isExcluded("org.apache.kafka")).isTrue();
    assertThat(engine.isExcluded("org.apache.kafka.clients.NetworkClient")).isTrue();
  }

  @Test
  void prefixMatchRespectsHierarchyBoundaryAndNeverMatchesSiblingPackage() {
    // A bare startsWith would wrongly exclude the sibling package "org.apache.kafkaconnect".
    AlertEngine engine = engineWithExclusions(List.of("org.apache.kafka"));
    assertThat(engine.isExcluded("org.apache.kafkaconnect.Worker")).isFalse();
  }

  @Test
  void noAlertMarkerGatesTheEventOut() {
    AlertEngine engine = engineWithExclusions(List.of());
    AlertEvent withMarker =
        new AlertEvent(
            "com.acme.Foo", "boom", 0L, List.of(), List.of(), Set.of(AlertEngine.NO_ALERT_MARKER_NAME));
    assertThat(engine.hasNoAlertMarker(withMarker)).isTrue();
  }

  @Test
  void absentNoAlertMarkerDoesNotGate() {
    AlertEngine engine = engineWithExclusions(List.of());
    AlertEvent noMarkers =
        new AlertEvent("com.acme.Foo", "boom", 0L, List.of(), List.of(), Set.of());
    AlertEvent otherMarker =
        new AlertEvent("com.acme.Foo", "boom", 0L, List.of(), List.of(), Set.of("AUDIT"));
    assertThat(engine.hasNoAlertMarker(noMarkers)).isFalse();
    assertThat(engine.hasNoAlertMarker(otherMarker)).isFalse();
  }
}
