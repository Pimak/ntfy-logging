package io.github.pimak.ntfy.logback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.AlertEvent;

/**
 * Verifies {@link LogbackEventMapper} snapshots a genuine {@code ILoggingEvent}/{@code
 * IThrowableProxy} chain into an {@link AlertEvent} of the correct shape (cause chain surface&rarr;
 * root, UNLIMITED root-cause frames, flattened marker names). Ported from the original {@code
 * PayloadBuilderTest}, but assertions now target the framework-neutral event rather than a formatted
 * body — body/title formatting moved into ntfy-core.
 */
class LogbackEventMapperTest {

  private static final Logger LOGGER =
      (Logger) LoggerFactory.getLogger(LogbackEventMapperTest.class);

  @Test
  void map_wrappedException_causeChainRunsSurfaceToRoot() {
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", surface, null);

    AlertEvent mapped = LogbackEventMapper.map(event);

    assertThat(mapped.causeChain()).hasSize(2);
    assertThat(mapped.causeChain().get(0).className()).isEqualTo("java.lang.RuntimeException");
    assertThat(mapped.causeChain().get(0).message()).isEqualTo("surface");
    // Last element is the ROOT cause — the engine draws the title suffix and stack frames from it.
    assertThat(mapped.causeChain().get(1).className()).isEqualTo("java.lang.IllegalStateException");
    assertThat(mapped.causeChain().get(1).message()).isEqualTo("root cause");
  }

  @Test
  void map_noException_emptyCauseChainAndFrames() {
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", null, null);

    AlertEvent mapped = LogbackEventMapper.map(event);

    assertThat(mapped.causeChain()).isEmpty();
    assertThat(mapped.rootCauseFrames()).isEmpty();
  }

  @Test
  void map_copiesMessageLoggerAndTimestamp() {
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom happened", null, null);

    AlertEvent mapped = LogbackEventMapper.map(event);

    assertThat(mapped.formattedMessage()).isEqualTo("boom happened");
    assertThat(mapped.loggerName()).isEqualTo(LOGGER.getName());
    assertThat(mapped.timestampMillis()).isEqualTo(event.getTimeStamp());
  }

  @Test
  void map_formatsArgumentsIntoMessage() {
    LoggingEvent event =
        new LoggingEvent(
            Logger.class.getName(),
            LOGGER,
            Level.ERROR,
            "user {} failed {} times",
            null,
            new Object[] {"alice", 3});

    AlertEvent mapped = LogbackEventMapper.map(event);

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
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, "boom", surface, null);

    AlertEvent mapped = LogbackEventMapper.map(event);

    // The mapper never caps frames — capping is the engine's job (maxStackFrames). The frames come
    // from the ROOT cause, so the very last synthetic frame must be present.
    assertThat(mapped.rootCauseFrames()).hasSize(500);
    assertThat(mapped.rootCauseFrames().get(499)).contains("method499");
  }

  @Test
  void map_directNoAlertMarker_yieldsNoAlertInMarkerNames() {
    LoggerContext context = new LoggerContext();
    Logger logger = context.getLogger(LogbackEventMapperTest.class);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "boom", null, null);
    event.addMarker(MarkerFactory.getMarker(AlertEngine.NO_ALERT_MARKER_NAME));

    AlertEvent mapped = LogbackEventMapper.map(event);

    assertThat(mapped.markerNames()).contains("NO_ALERT");
  }

  @Test
  void map_compositeMarkerReferencingNoAlertAsChild_yieldsNoAlertInMarkerNames() {
    LoggerContext context = new LoggerContext();
    Logger logger = context.getLogger(LogbackEventMapperTest.class);
    Marker child = MarkerFactory.getMarker(AlertEngine.NO_ALERT_MARKER_NAME);
    Marker composite = MarkerFactory.getMarker("PARENT_MARKER");
    composite.add(child);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "boom", null, null);
    event.addMarker(composite);

    AlertEvent mapped = LogbackEventMapper.map(event);

    // The engine gates on markerNames.contains("NO_ALERT"); the mapper must flatten the composite's
    // child into the set for that gate to fire.
    assertThat(mapped.markerNames()).contains("PARENT_MARKER", "NO_ALERT");
  }

  @Test
  void map_unrelatedMarker_doesNotContainNoAlert() {
    LoggerContext context = new LoggerContext();
    Logger logger = context.getLogger(LogbackEventMapperTest.class);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "boom", null, null);
    event.addMarker(MarkerFactory.getMarker("SOME_OTHER_MARKER"));

    AlertEvent mapped = LogbackEventMapper.map(event);

    assertThat(mapped.markerNames()).containsExactly("SOME_OTHER_MARKER");
  }

  @Test
  void map_noMarkers_emptyMarkerNames() {
    LoggerContext context = new LoggerContext();
    Logger logger = context.getLogger(LogbackEventMapperTest.class);
    LoggingEvent event =
        new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "boom", null, null);

    AlertEvent mapped = LogbackEventMapper.map(event);

    assertThat(mapped.markerNames()).isEmpty();
  }
}
