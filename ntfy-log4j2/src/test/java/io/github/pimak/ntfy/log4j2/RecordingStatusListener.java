package io.github.pimak.ntfy.log4j2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Test-only sink for log4j2's {@link StatusLogger}, the channel {@link Log4j2Diagnostics} writes
 * the engine's self-diagnostics to. This is the log4j2 equivalent of reading Logback's
 * {@code StatusManager.getCopyOfStatusList()}.
 *
 * <p>Registering a listener is what makes the status stream observable at all: {@code
 * StatusLogger.getStatusData()}'s ring buffer is disabled by default in modern log4j2, and the
 * effective status level is the least specific of the fallback console listener and all registered
 * listeners — so a {@link Level#TRACE} listener both enables and captures INFO/WARN lines.
 * Registering also suppresses the fallback console listener, keeping the build output clean.
 *
 * <p>Always {@link #detach()} in an {@code @AfterEach}: the StatusLogger is a process-wide
 * singleton and a leaked listener would keep capturing (and keep raising the status level) for
 * every later test in the JVM.
 */
final class RecordingStatusListener implements StatusListener {

  private final List<StatusData> events = Collections.synchronizedList(new ArrayList<>());

  /** Registers a fresh listener on the process-wide StatusLogger. */
  static RecordingStatusListener attach() {
    RecordingStatusListener listener = new RecordingStatusListener();
    StatusLogger.getLogger().registerListener(listener);
    return listener;
  }

  /** Deregisters this listener. Safe to call on an already-removed listener. */
  void detach() {
    StatusLogger.getLogger().removeListener(this);
  }

  /** Every status message captured so far, in emission order. */
  List<String> messages() {
    List<String> rendered = new ArrayList<>();
    synchronized (events) {
      for (StatusData data : events) {
        rendered.add(data.getMessage().getFormattedMessage());
      }
    }
    return rendered;
  }

  /** Captured messages at exactly {@code level}. */
  List<String> messagesAt(Level level) {
    List<String> rendered = new ArrayList<>();
    synchronized (events) {
      for (StatusData data : events) {
        if (data.getLevel() == level) {
          rendered.add(data.getMessage().getFormattedMessage());
        }
      }
    }
    return rendered;
  }

  @Override
  public void log(StatusData data) {
    events.add(data);
  }

  @Override
  public Level getStatusLevel() {
    return Level.TRACE;
  }

  @Override
  public void close() {
    // Nothing to release; detach() is the lifecycle hook tests use.
  }
}
