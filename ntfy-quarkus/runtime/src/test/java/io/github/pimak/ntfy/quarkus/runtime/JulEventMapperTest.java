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

  @Test
  void fallsBackToRawMessageOnBadPattern() {
    LogRecord record = new LogRecord(Level.SEVERE, "bad {pattern");
    record.setParameters(new Object[] {"x"});

    AlertEvent event = JulEventMapper.map(record);

    assertThat(event.formattedMessage()).isEqualTo("bad {pattern");
  }
}
