package io.github.pimak.ntfy.log4j2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;

import org.apache.logging.log4j.core.LogEvent;

/**
 * Builds the "throwable survives only as a {@code ThrowableProxy}" event shape the mapper's
 * fallback path exists for, by doing what actually produces it in the wild: a serialization round
 * trip, the same one a {@code SocketAppender}/JMS relay performs.
 *
 * <p>There is no shortcut. {@code Log4jLogEvent.Builder.setThrownProxy(..)} looks like one but is a
 * no-op in modern log4j2 (deprecated and gutted), so a test that used it would build an event with
 * NO throwable at all and pass for the wrong reason. Serializing is the only construction that
 * yields {@code getThrown() == null} together with a populated {@code getThrownProxy()}: the
 * proxy is a serialized field while the live {@code Throwable} is transient.
 */
final class RelayedLogEvents {

  private RelayedLogEvents() {}

  /**
   * Round-trips {@code event} through Java serialization, returning the deserialized copy whose
   * throwable exists only as a {@code ThrowableProxy}.
   */
  static LogEvent relay(LogEvent event) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
        out.writeObject(event);
      }
      try (ObjectInputStream in =
          new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        return (LogEvent) in.readObject();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A wrapped exception whose ROOT cause carries synthetic frames that share nothing with the
   * surface's real ones. log4j2 trims a nested proxy down to the frames it does not share with its
   * wrapper, so a root cause built inline would serialize to zero frames; synthetic frames keep the
   * assertion about UNLIMITED root-cause frames deterministic.
   */
  static RuntimeException wrappedExceptionWithDistinctRootFrames(int rootFrameCount) {
    IllegalStateException root = new IllegalStateException("proxied root");
    StackTraceElement[] frames = new StackTraceElement[rootFrameCount];
    for (int i = 0; i < rootFrameCount; i++) {
      frames[i] =
          new StackTraceElement("com.example.Deep" + i, "method" + i, "Deep" + i + ".java", i);
    }
    root.setStackTrace(frames);
    return new RuntimeException("proxied surface", root);
  }
}
