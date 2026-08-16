package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Covers the {@code level} component added to {@link AlertEvent}: the compatibility of both
 * pre-existing constructor signatures (this record is public API and code compiled against an
 * earlier release must keep linking), and the deliberate direction of the {@code null} fallback.
 */
class AlertEventLevelTest {

  @Test
  void legacySixArgConstructor_stillCompiles_andIsAnErrorEvent() {
    // This call site IS the test: it is the canonical signature of the release before mdcValues
    // existed, so it compiling at all proves source compatibility, and the explicit constructor
    // behind it preserves the (String,String,J,List,List,Set)V descriptor for binary compatibility.
    AlertEvent event =
        new AlertEvent(
            "com.acme.Foo",
            "boom",
            0L,
            List.of(new AlertEvent.Cause("java.lang.IllegalStateException", "bad")),
            List.of("com.acme.Foo.run(Foo.java:1)"),
            Set.of("AUDIT"));

    assertThat(event.level()).isEqualTo(AlertLevel.ERROR);
    assertThat(event.mdcValues()).isEmpty();
  }

  @Test
  void legacySevenArgConstructor_stillCompiles_andIsAnErrorEvent() {
    // Likewise for the signature shipped with mdcValues but before level.
    AlertEvent event =
        new AlertEvent(
            "com.acme.Foo",
            "boom",
            0L,
            List.of(),
            List.of(),
            Set.of(),
            Map.of("tenant", "acme"));

    assertThat(event.level()).isEqualTo(AlertLevel.ERROR);
    assertThat(event.mdcValues()).containsExactly(Map.entry("tenant", "acme"));
  }

  @Test
  void ofFactory_isAnErrorEvent() {
    assertThat(AlertEvent.of("com.acme.Foo", "boom", 0L).level()).isEqualTo(AlertLevel.ERROR);
  }

  @Test
  void nullLevel_fallsBackToError_neverToWarn() {
    // Direction matters: an adapter (or third-party caller) that omits the level must land on the
    // always-on route. Falling back to WARN would silently divert its events onto the opt-in quiet
    // channel — and, when no warn-topic is configured, drop them entirely.
    AlertEvent event =
        new AlertEvent("com.acme.Foo", "boom", 0L, List.of(), List.of(), Set.of(), Map.of(), null);

    assertThat(event.level()).isEqualTo(AlertLevel.ERROR);
  }

  @Test
  void withLevel_replacesOnlyTheLevel() {
    AlertEvent error =
        new AlertEvent(
            "com.acme.Foo",
            "boom",
            7L,
            List.of(new AlertEvent.Cause("java.lang.IllegalStateException", "bad")),
            List.of("com.acme.Foo.run(Foo.java:1)"),
            Set.of("AUDIT"),
            Map.of("tenant", "acme"));

    AlertEvent warn = error.withLevel(AlertLevel.WARN);

    assertThat(warn.level()).isEqualTo(AlertLevel.WARN);
    assertThat(warn).isEqualTo(error.withLevel(AlertLevel.WARN));
    assertThat(warn.withLevel(AlertLevel.ERROR)).isEqualTo(error);
  }
}
