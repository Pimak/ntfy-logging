package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Covers the {@code mdcValues} component added to {@link AlertEvent}: the compatibility of the
 * pre-existing 6-argument constructor (this record is public API and code compiled against an
 * earlier release must keep linking), and the belt-and-braces normalization that makes even a
 * hand-built event carrying a raw MDC map safe to render into an alert body.
 */
class AlertEventMdcTest {

  @Test
  void legacySixArgConstructor_stillCompiles_andYieldsEmptyMdc() {
    // This call site IS the test: it is the exact canonical signature of the previous release, so
    // it compiling at all proves source compatibility, and the explicit constructor behind it
    // preserves the (String,String,J,List,List,Set)V descriptor for binary compatibility too.
    AlertEvent event =
        new AlertEvent(
            "com.acme.Foo",
            "boom",
            0L,
            List.of(new AlertEvent.Cause("java.lang.IllegalStateException", "bad")),
            List.of("com.acme.Foo.run(Foo.java:1)"),
            Set.of("AUDIT"));

    assertThat(event.mdcValues()).isEmpty();
    assertThat(event.markerNames()).containsExactly("AUDIT");
  }

  @Test
  void of_factory_yieldsEmptyMdc() {
    assertThat(AlertEvent.of("com.acme.Foo", "boom", 0L).mdcValues()).isEmpty();
  }

  @Test
  void nullMdc_normalizesToEmptyMap() {
    AlertEvent event =
        new AlertEvent("com.acme.Foo", "boom", 0L, null, null, null, null);

    assertThat(event.mdcValues()).isNotNull().isEmpty();
  }

  @Test
  void mdcValues_preserveInsertionOrder_andAreUnmodifiable() {
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put("tenant", "acme");
    mdc.put("correlationId", "c-1");
    mdc.put("requestId", "r-1");

    AlertEvent event =
        new AlertEvent("com.acme.Foo", "boom", 0L, List.of(), List.of(), Set.of(), mdc);

    // Order is a contract: it is the order the lines are rendered in, so it must survive the
    // normalization (which is why MdcProjector never uses order-losing Map.copyOf).
    assertThat(new ArrayList<>(event.mdcValues().keySet()))
        .containsExactly("tenant", "correlationId", "requestId");
    assertThatThrownBy(() -> event.mdcValues().put("injected", "x"))
        .isInstanceOf(UnsupportedOperationException.class);
    // A later mutation of the caller's map must not reach the event either.
    mdc.put("added-after", "late");
    assertThat(event.mdcValues()).doesNotContainKey("added-after");
  }

  @Test
  void directlyBuiltEvent_withHostileMdc_isStillSanitized() {
    // The scenario: a third-party adapter that skipped MdcProjector.project and handed over the raw
    // context map. Selection was its job; making the content safe is this boundary's job.
    Map<String, String> hostile = new LinkedHashMap<>();
    for (int i = 0; i < 50; i++) {
      hostile.put("k" + i, "line" + i + "\nCaused by: java.lang.Forged");
    }

    AlertEvent event =
        new AlertEvent("com.acme.Foo", "boom", 0L, List.of(), List.of(), Set.of(), hostile);

    assertThat(event.mdcValues()).hasSizeLessThanOrEqualTo(MdcProjector.MAX_KEYS);
    assertThat(event.mdcValues().values())
        .allSatisfy(
            v ->
                assertThat(v)
                    .doesNotContain("\n")
                    .hasSizeLessThanOrEqualTo(MdcProjector.MAX_VALUE_CHARS));
    int totalChars =
        event.mdcValues().entrySet().stream()
            .mapToInt(e -> e.getKey().length() + e.getValue().length())
            .sum();
    assertThat(totalChars).isLessThanOrEqualTo(MdcProjector.MAX_TOTAL_CHARS);
  }
}
