package io.github.pimak.ntfy.jul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.pimak.ntfy.core.AlertEvent;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;
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

    AlertEvent event = JulEventMapper.map(record, List.of());

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

    AlertEvent event = JulEventMapper.map(record, List.of());

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

    AlertEvent event = JulEventMapper.map(record, List.of());

    assertThat(event.causeChain()).hasSize(2);
    assertThat(event.causeChain().get(0).message()).isEqualTo("a");
    assertThat(event.causeChain().get(1).message()).isEqualTo("b");
  }

  @Test
  void fallsBackToRawMessageOnBadPattern() {
    LogRecord record = new LogRecord(Level.SEVERE, "bad {pattern");
    record.setParameters(new Object[] {"x"});

    AlertEvent event = JulEventMapper.map(record, List.of());

    assertThat(event.formattedMessage()).isEqualTo("bad {pattern");
  }

  // ---------------------------------------------------------------------------------------------
  // include-mdc-keys. Plain JUL has no MDC; JBoss LogManager's ExtLogRecord does, and is reached
  // reflectively (see ExtLogRecordMdc). The org.jboss.logmanager.ExtLogRecord used below is this
  // module's dependency-free test stub — see its header comment.
  // ---------------------------------------------------------------------------------------------

  /** Fail closed: an allow-list against a plain JUL record yields nothing, and never throws. */
  @Test
  void map_plainLogRecord_withAllowList_yieldsEmptyMdcValues() {
    LogRecord record = new LogRecord(Level.SEVERE, "plain");
    record.setLoggerName("app");

    AlertEvent event = JulEventMapper.map(record, List.of("requestId", "tenant"));

    assertThat(event.mdcValues()).isEmpty();
  }

  /** The point of the feature: named keys in, everything else — value and all — left behind. */
  @Test
  void map_extLogRecord_projectsOnlyAllowListedKeys() {
    ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");
    record.withMdc("requestId", "req-42").withMdc("sessionToken", "super-secret");

    AlertEvent event = JulEventMapper.map(record, List.of("requestId"));

    assertThat(event.mdcValues()).containsEntry("requestId", "req-42");
    assertThat(event.mdcValues()).doesNotContainKey("sessionToken");
    assertThat(event.mdcValues().values()).doesNotContain("super-secret");
  }

  /** Frameworks hand out subclasses; the superclass walk must still find the accessor. */
  @Test
  void map_extLogRecordSubclass_isStillDetected() {
    FrameworkRecord record = new FrameworkRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");
    record.withMdc("requestId", "req-7");

    AlertEvent event = JulEventMapper.map(record, List.of("requestId"));

    assertThat(event.mdcValues()).containsExactly(entry("requestId", "req-7"));
  }

  /** A hostile/broken MDC must degrade to "key absent", never break the logging call. */
  @Test
  void map_extLogRecordThrowingFromGetMdc_isTreatedAsAbsent_andNeverThrows() {
    ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");
    record.withMdc("requestId", "req-42").failingWith(new IllegalStateException("mdc is broken"));

    AlertEvent event = JulEventMapper.map(record, List.of("requestId"));

    assertThat(event.mdcValues()).isEmpty();
  }

  /** Feature unset costs nothing: no reflection, and the record's MDC is not touched even once. */
  @Test
  void map_withEmptyAllowList_neverConsultsTheRecordsMdc() {
    ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");
    record.withMdc("requestId", "req-42");

    assertThat(JulEventMapper.map(record, List.of()).mdcValues()).isEmpty();
    assertThat(JulEventMapper.map(record, null).mdcValues()).isEmpty();
    assertThat(record.mdcCallCount()).isZero();
  }

  /**
   * FQCN matching, not duck-typing: a {@link LogRecord} subclass that merely HAPPENS to declare a
   * {@code getMdc(String)} is not JBoss LogManager and its unknown method must never be invoked.
   */
  @Test
  void unrelatedLogRecordSubclassWithGetMdc_isIgnored() {
    ImpostorRecord record = new ImpostorRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");

    AlertEvent event = JulEventMapper.map(record, List.of("requestId"));

    assertThat(event.mdcValues()).isEmpty();
    assertThat(record.consulted).isFalse();
  }

  /** The allow-list order IS the body's rendering order, regardless of MDC insertion order. */
  @Test
  void map_mdcValues_followConfiguredOrder() {
    ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "boom");
    record.setLoggerName("app");
    record.withMdc("alpha", "a").withMdc("beta", "b").withMdc("gamma", "c");

    AlertEvent event = JulEventMapper.map(record, List.of("gamma", "alpha", "beta"));

    assertThat(event.mdcValues().keySet()).containsExactly("gamma", "alpha", "beta");
  }

  /** Stands in for the framework subclasses of {@code ExtLogRecord} seen in the wild. */
  private static final class FrameworkRecord extends ExtLogRecord {
    private static final long serialVersionUID = 1L;

    FrameworkRecord(Level level, String msg) {
      super(level, msg);
    }
  }

  /** Right method shape, wrong class: must be ignored on the strength of its FQCN alone. */
  private static final class ImpostorRecord extends LogRecord {
    private static final long serialVersionUID = 1L;

    private transient boolean consulted;

    ImpostorRecord(Level level, String msg) {
      super(level, msg);
    }

    public String getMdc(String key) {
      consulted = true;
      return "leaked-" + key;
    }
  }
}
