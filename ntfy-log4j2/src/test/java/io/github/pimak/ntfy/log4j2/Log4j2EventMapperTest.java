package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.apache.logging.log4j.util.StringMap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.AlertEvent;

/**
 * Verifies {@link Log4j2EventMapper} snapshots a genuine {@code LogEvent} into an {@link AlertEvent}
 * of the correct shape (cause chain surface&rarr;root, UNLIMITED root-cause frames, flattened
 * marker names) and survives every degenerate event log4j2 can hand an appender: an empty root
 * logger name, a blank message, a circular cause chain, and a throwable that exists only as a
 * {@code ThrowableProxy}.
 */
class Log4j2EventMapperTest {

  private static final String LOGGER_NAME = "com.example.testapp.SimulatedConsumer";

  private static Log4jLogEvent.Builder event(String message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName(LOGGER_NAME)
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage(message))
        .setTimeMillis(1_700_000_000_000L);
  }

  @Test
  void map_wrappedException_causeChainRunsSurfaceToRoot() {
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    LogEvent logEvent = event("boom").setThrown(surface).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.causeChain()).hasSize(2);
    assertThat(mapped.causeChain().get(0).className()).isEqualTo("java.lang.RuntimeException");
    assertThat(mapped.causeChain().get(0).message()).isEqualTo("surface");
    // Last element is the ROOT cause — the engine draws the title suffix and stack frames from it.
    assertThat(mapped.causeChain().get(1).className()).isEqualTo("java.lang.IllegalStateException");
    assertThat(mapped.causeChain().get(1).message()).isEqualTo("root cause");
  }

  @Test
  void map_noException_emptyCauseChainAndFrames() {
    AlertEvent mapped = Log4j2EventMapper.map(event("boom").build(), List.of());

    assertThat(mapped.causeChain()).isEmpty();
    assertThat(mapped.rootCauseFrames()).isEmpty();
  }

  @Test
  void map_copiesMessageLoggerAndTimestamp() {
    LogEvent logEvent = event("boom happened").build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.formattedMessage()).isEqualTo("boom happened");
    assertThat(mapped.loggerName()).isEqualTo(LOGGER_NAME);
    assertThat(mapped.timestampMillis()).isEqualTo(1_700_000_000_000L);
  }

  @Test
  void map_parameterizedMessage_isRenderedWithArguments() {
    LogEvent logEvent =
        Log4jLogEvent.newBuilder()
            .setLoggerName(LOGGER_NAME)
            .setLevel(Level.ERROR)
            .setMessage(new ParameterizedMessage("user {} failed {} times", "alice", 3))
            .build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.formattedMessage()).isEqualTo("user alice failed 3 times");
  }

  @Test
  void map_rootCauseFramesAreUnlimited() {
    StackTraceElement[] frames = new StackTraceElement[500];
    for (int i = 0; i < frames.length; i++) {
      frames[i] =
          new StackTraceElement("com.example.Class" + i, "method" + i, "Class" + i + ".java", i);
    }
    IllegalStateException root = new IllegalStateException("deep root cause");
    root.setStackTrace(frames);
    RuntimeException surface = new RuntimeException("surface", root);

    AlertEvent mapped = Log4j2EventMapper.map(event("boom").setThrown(surface).build(), List.of());

    // The mapper never caps frames — capping is the engine's job (maxStackFrames). The frames come
    // from the ROOT cause, so the very last synthetic frame must be present.
    assertThat(mapped.rootCauseFrames()).hasSize(500);
    assertThat(mapped.rootCauseFrames().get(499)).contains("method499");
  }

  @Test
  void map_circularCauseChain_terminatesAndIsBounded() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    // Legal, and enough to hang a naive walker: first -> second -> first -> ...
    first.initCause(second);

    AlertEvent mapped = Log4j2EventMapper.map(event("boom").setThrown(second).build(), List.of());

    // The identity-based `seen` guard stops the walk the moment a link repeats.
    assertThat(mapped.causeChain()).hasSize(2);
    assertThat(mapped.causeChain()).extracting(AlertEvent.Cause::message)
        .containsExactly("second", "first");
  }

  @Test
  void map_thrownProxyOnly_producesTheSameShapeAsALiveThrowable() {
    LogEvent logEvent =
        RelayedLogEvents.relay(
            event("boom")
                .setThrown(RelayedLogEvents.wrappedExceptionWithDistinctRootFrames(300))
                .build());
    // A relayed event keeps only the rendered proxy — this is the fallback path's whole reason.
    assertThat(logEvent.getThrown()).isNull();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.causeChain()).hasSize(2);
    assertThat(mapped.causeChain().get(0).className()).isEqualTo("java.lang.RuntimeException");
    assertThat(mapped.causeChain().get(0).message()).isEqualTo("proxied surface");
    assertThat(mapped.causeChain().get(1).className()).isEqualTo("java.lang.IllegalStateException");
    assertThat(mapped.causeChain().get(1).message()).isEqualTo("proxied root");
    // ROOT-cause frames, unlimited, and rendered WITHOUT log4j2's "~[classes/:?]" packaging
    // annotation so the proxy path reads identically to the live-throwable path.
    assertThat(mapped.rootCauseFrames()).hasSize(300);
    assertThat(mapped.rootCauseFrames().get(299)).contains("method299").doesNotContain("~[");
  }

  @Test
  void map_thrownProxyWithFullyTrimmedRootCause_fallsBackToTheSurfaceTrace() {
    // Root cause constructed inline with its wrapper: every one of its frames is shared, so log4j2
    // trims the nested proxy to zero frames. An alert must still carry a stack trace.
    RuntimeException surface = new RuntimeException("surface", new IllegalStateException("root"));
    LogEvent logEvent = RelayedLogEvents.relay(event("boom").setThrown(surface).build());

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.causeChain()).hasSize(2);
    assertThat(mapped.rootCauseFrames()).isNotEmpty();
    assertThat(mapped.rootCauseFrames().get(0)).contains(Log4j2EventMapperTest.class.getName());
  }

  @Test
  void map_rootLoggerEmptyName_fallsBackToRoot() {
    // log4j2 names the root logger with the EMPTY STRING; AlertEvent rejects a blank logger name.
    LogEvent logEvent =
        Log4jLogEvent.newBuilder()
            .setLoggerName("")
            .setLevel(Level.ERROR)
            .setMessage(new SimpleMessage("boom"))
            .build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.loggerName()).isEqualTo("ROOT");
  }

  @Test
  void map_blankMessageWithThrowable_fallsBackToSurfaceCauseDescription() {
    // logger.error("", e) is idiomatic log4j2 and would otherwise blow up the AlertEvent ctor.
    LogEvent logEvent =
        event("").setThrown(new IllegalStateException("bad state")).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.formattedMessage())
        .isEqualTo("java.lang.IllegalStateException: bad state");
  }

  @Test
  void map_blankMessageWithoutThrowable_fallsBackToPlaceholder() {
    AlertEvent mapped = Log4j2EventMapper.map(event("   ").build(), List.of());

    assertThat(mapped.formattedMessage()).isEqualTo("(no message)");
  }

  @Test
  void map_nullMessage_fallsBackToPlaceholder() {
    LogEvent logEvent =
        Log4jLogEvent.newBuilder().setLoggerName(LOGGER_NAME).setLevel(Level.ERROR).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.formattedMessage()).isEqualTo("(no message)");
  }

  @Test
  void map_directNoAlertMarker_yieldsNoAlertInMarkerNames() {
    LogEvent logEvent =
        event("boom").setMarker(MarkerManager.getMarker(AlertEngine.NO_ALERT_MARKER_NAME)).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.markerNames()).contains("NO_ALERT");
  }

  @Test
  void map_markerInheritingNoAlertAsParent_yieldsNoAlertInMarkerNames() {
    // log4j2 markers compose through PARENTS (the inverse direction of SLF4J's child references).
    Marker child =
        MarkerManager.getMarker("SILENT_CHILD")
            .setParents(MarkerManager.getMarker(AlertEngine.NO_ALERT_MARKER_NAME));
    LogEvent logEvent = event("boom").setMarker(child).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    // The engine gates on markerNames.contains("NO_ALERT"); the mapper must flatten the parent
    // graph into the set for that gate to fire.
    assertThat(mapped.markerNames()).contains("SILENT_CHILD", "NO_ALERT");
  }

  @Test
  void map_unrelatedMarker_doesNotContainNoAlert() {
    LogEvent logEvent = event("boom").setMarker(MarkerManager.getMarker("SOME_OTHER")).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of());

    assertThat(mapped.markerNames()).containsExactly("SOME_OTHER");
  }

  @Test
  void map_noMarkers_emptyMarkerNames() {
    AlertEvent mapped = Log4j2EventMapper.map(event("boom").build(), List.of());

    assertThat(mapped.markerNames()).isEmpty();
  }

  // --- MDC context projection (log4j2's ThreadContext) --------------------------------------------

  private static StringMap contextData(Map<String, ?> entries) {
    StringMap map = new SortedArrayStringMap();
    entries.forEach(map::putValue);
    return map;
  }

  @Test
  void map_withAllowList_projectsOnlyListedContextEntries() {
    LogEvent logEvent =
        event("boom")
            .setContextData(
                contextData(Map.of("correlation-id", "abc123", "password", "hunter2")))
            .build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of("correlation-id"));

    assertThat(mapped.mdcValues()).containsExactly(Map.entry("correlation-id", "abc123"));
    // The anti-leak assertion: an unlisted key never leaves the mapper, key or value.
    assertThat(mapped.mdcValues()).doesNotContainKey("password");
    assertThat(mapped.mdcValues().values()).doesNotContain("hunter2");
  }

  @Test
  void map_withEmptyAllowList_yieldsEmptyMdcValues() {
    LogEvent logEvent =
        event("boom").setContextData(contextData(Map.of("correlation-id", "abc123"))).build();

    assertThat(Log4j2EventMapper.map(logEvent, List.of()).mdcValues()).isEmpty();
    assertThat(Log4j2EventMapper.map(logEvent, null).mdcValues()).isEmpty();
  }

  @Test
  void map_mdcValues_followConfiguredOrder_notContextOrder() {
    LogEvent logEvent =
        event("boom").setContextData(contextData(Map.of("alpha", "1", "beta", "2"))).build();

    AlertEvent mapped = Log4j2EventMapper.map(logEvent, List.of("beta", "alpha"));

    assertThat(mapped.mdcValues().keySet()).containsExactly("beta", "alpha");
  }

  @Test
  void map_nonStringContextValue_isRenderedViaToString() {
    // An ObjectThreadContextMap may hold non-String values; a cast would throw on the logging path.
    LogEvent logEvent =
        event("boom").setContextData(contextData(Map.of("attempt", 42))).build();

    assertThat(Log4j2EventMapper.map(logEvent, List.of("attempt")).mdcValues())
        .containsExactly(Map.entry("attempt", "42"));
  }

  @Test
  void map_contextValueWithNewline_isNeutralized() {
    LogEvent logEvent =
        event("boom")
            .setContextData(contextData(Map.of("tenant", "acme\nCaused by: java.lang.Forged")))
            .build();

    String rendered = Log4j2EventMapper.map(logEvent, List.of("tenant")).mdcValues().get("tenant");

    assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
  }

  @Test
  void map_eventWithoutContextData_yieldsEmptyMdcValues() {
    // A LogEvent whose getContextData() returns null must degrade to "no context", never throw.
    LogEvent bare =
        new Log4jLogEvent.Builder(event("boom").build()) {
          @Override
          public Log4jLogEvent build() {
            return new Log4jLogEvent.Builder(super.build()) {}.build();
          }
        }.build();

    assertThat(Log4j2EventMapper.map(bare, List.of("tenant")).mdcValues()).isEmpty();
  }
}
