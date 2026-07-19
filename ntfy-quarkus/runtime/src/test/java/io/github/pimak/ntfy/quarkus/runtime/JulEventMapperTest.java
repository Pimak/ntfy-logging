package io.github.pimak.ntfy.quarkus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pimak.ntfy.core.AlertEvent;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class JulEventMapperTest {

  @Test
  void mapsNestedCauseChainSurfaceToRoot() {
    Throwable root = new IllegalStateException("root boom");
    Throwable middle = new RuntimeException("middle", root);
    Throwable surface = new RuntimeException("surface", middle);

    LogRecord record = new LogRecord(Level.SEVERE, "it failed");
    record.setLoggerName("com.example.Service");
    record.setThrown(surface);
    record.setMillis(123_456L);

    AlertEvent event = JulEventMapper.map(record);

    assertThat(event.loggerName()).isEqualTo("com.example.Service");
    assertThat(event.formattedMessage()).isEqualTo("it failed");
    assertThat(event.timestampMillis()).isEqualTo(123_456L);
    assertThat(event.markerNames()).isEmpty();

    assertThat(event.causeChain())
        .extracting(AlertEvent.Cause::className)
        .containsExactly(
            RuntimeException.class.getName(),
            RuntimeException.class.getName(),
            IllegalStateException.class.getName());
    assertThat(event.causeChain().get(2).message()).isEqualTo("root boom");

    // Root-cause frames are rendered (unlimited) and include this test method's frame.
    assertThat(event.rootCauseFrames()).isNotEmpty();
    assertThat(event.rootCauseFrames().get(0)).contains("JulEventMapperTest");
  }

  @Test
  void mapsMessageWithParametersUsingMessageFormat() {
    LogRecord record = new LogRecord(Level.SEVERE, "user {0} did {1}");
    record.setLoggerName("app");
    record.setParameters(new Object[] {"alice", "delete"});

    AlertEvent event = JulEventMapper.map(record);

    assertThat(event.formattedMessage()).isEqualTo("user alice did delete");
    assertThat(event.causeChain()).isEmpty();
    assertThat(event.rootCauseFrames()).isEmpty();
  }

  // DoS guard: a circular cause chain is legally constructible with initCause and, unguarded,
  // would loop the logging thread until OOM — the walk must terminate and map each cause once.
  @Test
  void circularCauseChain_terminatesAndMapsEachCauseOnce() {
    Exception a = new Exception("a");
    Exception b = new Exception("b");
    a.initCause(b);
    b.initCause(a);

    LogRecord record = new LogRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");
    record.setThrown(a);

    AlertEvent event = JulEventMapper.map(record);

    assertThat(event.causeChain()).hasSize(2);
    assertThat(event.causeChain().get(0).message()).isEqualTo("a");
    assertThat(event.causeChain().get(1).message()).isEqualTo("b");
  }

  @Test
  void fallsBackToRawMessageOnBadPattern() {
    LogRecord record = new LogRecord(Level.SEVERE, "bad {pattern");
    record.setParameters(new Object[] {"x"});

    AlertEvent event = JulEventMapper.map(record);

    assertThat(event.formattedMessage()).isEqualTo("bad {pattern");
  }
}
