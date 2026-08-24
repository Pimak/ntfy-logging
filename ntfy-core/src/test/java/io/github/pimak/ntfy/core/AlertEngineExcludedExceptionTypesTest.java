package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code excluded-exception-types} deny-list. The gate is a pure function of the config
 * and the event's cause chain — no HTTP, no lifecycle.
 *
 * <p>The tests that matter are the wrapped ones: a client disconnect virtually never arrives as a
 * bare {@code ClientAbortException}, it arrives wrapped in whatever the framework threw around it.
 * A gate that only inspected the surface throwable would pass every real-world case straight
 * through and the deny-list would look configured while doing nothing.
 */
class AlertEngineExcludedExceptionTypesTest {

  private static final String ABORT = "org.apache.catalina.connector.ClientAbortException";

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static AlertEngine engineExcluding(List<String> types) {
    return new AlertEngine(
        NtfyConfig.builder()
            .url("https://ntfy.example.com")
            .topic("t")
            .excludedExceptionTypes(types)
            .build(),
        NO_OP);
  }

  private static AlertEvent eventWithChain(String... classNames) {
    List<AlertEvent.Cause> chain =
        java.util.Arrays.stream(classNames).map(c -> new AlertEvent.Cause(c, "boom")).toList();
    return new AlertEvent(
        "com.acme.OrderService", "failed", 1L, chain, List.of(), java.util.Set.of(),
        java.util.Map.of(), AlertLevel.ERROR);
  }

  /** Captures the startup diagnostic stream so the status lines can be asserted on. */
  private static final class RecordingDiagnostics implements Diagnostics {
    final java.util.List<String> infos = new java.util.ArrayList<>();
    final java.util.List<String> warns = new java.util.ArrayList<>();

    @Override
    public void info(String msg) {
      infos.add(msg);
    }

    @Override
    public void warn(String msg) {
      warns.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {}
  }

  private static RecordingDiagnostics startWith(List<String> types) {
    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine =
        new AlertEngine(
            NtfyConfig.builder()
                .url("https://ntfy.example.com")
                .topic("t")
                .excludedExceptionTypes(types)
                .build(),
            diagnostics);
    engine.start();
    engine.stop();
    return diagnostics;
  }

  @Test
  void configuredTypesAreEchoedAtStartup() {
    // An entry that matches nothing suppresses nothing, and an inert deny-list is otherwise
    // indistinguishable from a working one: no error, no non-2xx, just alerts that keep arriving.
    // Echoing the list is what lets an operator confirm what the engine actually loaded.
    RecordingDiagnostics diagnostics = startWith(List.of(ABORT));

    assertThat(diagnostics.infos).anySatisfy(line -> assertThat(line).contains(ABORT));
  }

  @Test
  void anUnsetDenyListAddsNothingToTheDiagnosticStream() {
    // The feature is opt-in, so the default boot must look exactly as it did before it existed —
    // the same rule the include-mdc-keys line follows.
    RecordingDiagnostics diagnostics = startWith(List.of());

    assertThat(diagnostics.infos)
        .noneSatisfy(line -> assertThat(line).containsIgnoringCase("excluded exception"));
    assertThat(diagnostics.warns).isEmpty();
  }

  @Test
  void anEntryWithoutAPackageIsCalledOut() {
    // The mistake this key invites. Matching is on the fully qualified name, so a bare simple name
    // can never match — and would sit there looking configured while suppressing nothing.
    RecordingDiagnostics diagnostics = startWith(List.of("ClientAbortException"));

    assertThat(diagnostics.warns).isNotEmpty();
  }

  @Test
  void aFullyQualifiedEntryIsNotCalledOut() {
    assertThat(startWith(List.of(ABORT)).warns).isEmpty();
  }

  @Test
  void noConfiguredTypesExcludesNothing() {
    AlertEngine engine = engineExcluding(List.of());
    assertThat(engine.hasExcludedExceptionType(eventWithChain(ABORT))).isFalse();
  }

  @Test
  void surfaceThrowableMatches() {
    AlertEngine engine = engineExcluding(List.of(ABORT));
    assertThat(engine.hasExcludedExceptionType(eventWithChain(ABORT))).isTrue();
  }

  @Test
  void wrappedThrowableMatchesAnywhereInTheChain() {
    AlertEngine engine = engineExcluding(List.of(ABORT));
    assertThat(
            engine.hasExcludedExceptionType(
                eventWithChain("java.lang.RuntimeException", "java.io.IOException", ABORT)))
        .isTrue();
  }

  @Test
  void rootCauseMatchesEvenUnderSeveralWrappers() {
    AlertEngine engine = engineExcluding(List.of(ABORT));
    assertThat(
            engine.hasExcludedExceptionType(
                eventWithChain("a.A", "b.B", "c.C", "d.D", ABORT)))
        .isTrue();
  }

  @Test
  void unrelatedChainPasses() {
    AlertEngine engine = engineExcluding(List.of(ABORT));
    assertThat(
            engine.hasExcludedExceptionType(
                eventWithChain("java.lang.IllegalStateException", "java.io.IOException")))
        .isFalse();
  }

  @Test
  void eventWithoutThrowablePasses() {
    AlertEngine engine = engineExcluding(List.of(ABORT));
    assertThat(engine.hasExcludedExceptionType(eventWithChain())).isFalse();
  }

  @Test
  void matchIsOnTheFullyQualifiedNameAndNeverOnTheSimpleName() {
    // A simple-name match would make "ClientAbortException" silently exclude an unrelated
    // com.acme.ClientAbortException. The configured value is an FQCN, matched as one.
    AlertEngine engine = engineExcluding(List.of("ClientAbortException"));
    assertThat(engine.hasExcludedExceptionType(eventWithChain(ABORT))).isFalse();
  }

  @Test
  void matchIsExactAndNeverAPrefix() {
    // Unlike excluded-loggers, which is deliberately prefix-based, a type name is matched whole:
    // "java.io.IOException" must not swallow "java.io.IOExceptionSubclassOfSomeone".
    AlertEngine engine = engineExcluding(List.of("java.io.IOException"));
    assertThat(engine.hasExcludedExceptionType(eventWithChain("java.io.IOExceptionish")))
        .isFalse();
  }

  @Test
  void anyOfSeveralConfiguredTypesMatches() {
    AlertEngine engine = engineExcluding(List.of("x.Gone", ABORT, "y.Away"));
    assertThat(engine.hasExcludedExceptionType(eventWithChain("wrap.W", ABORT))).isTrue();
  }

  @Test
  void nullClassNameInAChainLinkIsSurvivable() {
    // AlertEvent.Cause documents className as possibly null in degenerate cases.
    AlertEngine engine = engineExcluding(List.of(ABORT));
    AlertEvent event =
        new AlertEvent(
            "com.acme.OrderService", "failed", 1L,
            List.of(new AlertEvent.Cause(null, "boom"), new AlertEvent.Cause(ABORT, "boom")),
            List.of(), java.util.Set.of(), java.util.Map.of(), AlertLevel.ERROR);
    assertThat(engine.hasExcludedExceptionType(event)).isTrue();
  }
}
