package io.github.pimak.ntfy.jul;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Function;
import java.util.logging.LogRecord;

/**
 * Bridges the {@code include-mdc-keys} feature onto {@code java.util.logging}, which has no MDC of
 * its own.
 *
 * <p>Plain JUL's {@link LogRecord} carries no diagnostic context at all. JBoss LogManager — and
 * therefore Quarkus, which funnels every log record through it — does: its {@code
 * org.jboss.logmanager.ExtLogRecord} subclass exposes an MDC. This class reaches that MDC by
 * reflection so that {@code ntfy-jul} keeps its "ntfy-core + the JDK's {@code java.util.logging},
 * nothing else" dependency promise (see the {@code bannedDependencies} allow-list in this module's
 * pom): there is no compile-time reference to JBoss LogManager anywhere, and when it is absent the
 * feature simply yields nothing.
 *
 * <h2>Why {@code getMdc(String)} and not {@code getMdcCopy()}</h2>
 *
 * <ul>
 *   <li>It reads <em>only the allow-listed keys</em>, which is the entire point of the feature.
 *       {@code getMdcCopy()} would materialize the whole MDC — session tokens, user identifiers and
 *       all — into a map we then hold, merely to throw almost all of it away.
 *   <li>It returns a {@code String}, so there is no unchecked {@code Map} cast and no defensive
 *       copy of an untrusted map to make.
 *   <li>It yields exactly the {@code Function<String, String>} lookup shape that logback's {@code
 *       getMDCPropertyMap()::get} yields, so {@link io.github.pimak.ntfy.core.MdcProjector#project}
 *       is called identically from both adapters — one set of guards (order, dedupe, caps,
 *       truncation, scrubbing), one place to audit them.
 * </ul>
 *
 * <h2>Why the accessor is resolved from the record's own class, not {@code Class.forName}</h2>
 *
 * <p>The obvious shape — a static holder that runs {@code Class.forName("org.jboss.logmanager…")}
 * once at class-init — is wrong here for three reasons:
 *
 * <ul>
 *   <li><strong>Zero classloader assumptions.</strong> Quarkus, Tomcat and application servers
 *       layer class loaders, and JBoss LogManager typically lives on the system loader while this
 *       jar may not (or vice versa). Resolving from {@code record.getClass()} uses the loader that
 *       actually produced the record, so the lookup cannot miss a class that is demonstrably
 *       present.
 *   <li><strong>Subclasses work.</strong> Frameworks hand out subclasses of {@code ExtLogRecord};
 *       walking the superclass chain finds the accessor on any of them.
 *   <li><strong>No static-init side effect.</strong> Nothing is loaded, and no failed lookup is
 *       cached forever, just because this class was touched.
 * </ul>
 *
 * <p>{@link #resolve} matches the <strong>exact</strong> fully qualified name {@value
 * #EXT_LOG_RECORD} rather than duck-typing on a {@code getMdc} method. That is what prevents this
 * adapter from invoking an unrelated {@code getMdc(String)} that happens to exist on some arbitrary
 * {@link LogRecord} subclass — an unknown method with an unknown contract must never be called on
 * the logging path.
 *
 * <p>Never calls {@code setAccessible}: only a {@code public} method on a {@code public} class is
 * ever invoked, so this works under a security manager, under strong module encapsulation, and
 * without any {@code --add-opens}.
 *
 * <p>Package-private on purpose — this is an implementation detail of {@link JulEventMapper}, not
 * API.
 */
final class ExtLogRecordMdc {

  /** The one class name reflected on; matched exactly, never by suffix and never duck-typed. */
  private static final String EXT_LOG_RECORD = "org.jboss.logmanager.ExtLogRecord";

  /**
   * An immutable resolution result: the record class it was resolved for, and the accessor found on
   * it — {@code getMdc == null} meaning "this record type has no JBoss LogManager MDC", which is a
   * result worth caching just as much as a hit is.
   */
  private record Accessor(Class<?> recordType, Method getMdc) {}

  /**
   * Single-entry cache. Holding the class and the method together in one immutable record and
   * publishing it through one {@code volatile} field means the hot path performs exactly one
   * volatile read and can never observe a torn pairing of "class A, method resolved from class B".
   * Two threads seeing different record types may re-resolve concurrently; that race is benign
   * (resolution is pure and idempotent, and the loser's value is simply overwritten). In practice
   * the logging pipeline of a given application is monomorphic — one record class — so this cache
   * hits essentially always.
   */
  private static volatile Accessor cached;

  private ExtLogRecordMdc() {}

  /**
   * Returns a per-key MDC lookup for {@code record}, or {@code null} when there is no JBoss
   * LogManager MDC behind it (plain JUL, or any other {@link LogRecord} implementation).
   *
   * <p>A {@code null} return is the caller's signal to skip the projection entirely; {@link
   * io.github.pimak.ntfy.core.MdcProjector#project} also treats a {@code null} lookup as "empty
   * map", so either way the fail-closed behavior is the same.
   *
   * @param record the record being mapped; {@code null} yields {@code null}
   * @return a lookup returning the MDC value for one key, or {@code null} when unavailable
   */
  static Function<String, String> lookupFor(LogRecord record) {
    if (record == null) {
      return null;
    }
    Class<?> type = record.getClass();

    Accessor accessor = cached; // the one and only read of the volatile on this path
    if (accessor == null || accessor.recordType() != type) {
      accessor = new Accessor(type, resolve(type));
      cached = accessor;
    }

    Method getMdc = accessor.getMdc();
    if (getMdc == null) {
      return null;
    }
    return key -> invoke(getMdc, record, key);
  }

  /**
   * Reads one key, failing closed and silently: a broken or hostile MDC implementation is treated
   * as "key absent" rather than allowed to escape into the logging caller. (The projector guards
   * against a throwing lookup too; this guard exists because the reflective call can also fail with
   * a <em>checked</em> exception the projector's {@code RuntimeException} catch would not cover.)
   *
   * <p>Deliberately a {@link Method} and not a {@code MethodHandle}: {@code MethodHandle.invoke}
   * declares {@code throws Throwable}, which would force a {@code catch (Throwable)} — swallowing
   * {@code Error}s — inside a logging handler. {@code Method.invoke} throws only the checked {@link
   * ReflectiveOperationException}, which catches cleanly and leaves {@code Error} alone.
   */
  private static String invoke(Method getMdc, LogRecord record, String key) {
    try {
      Object value = getMdc.invoke(record, key);
      // instanceof rather than a cast: the return type was verified at resolution time, but a
      // pattern match costs nothing and keeps a ClassCastException structurally impossible.
      return value instanceof String s ? s : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Walks {@code type}'s superclass chain for the exact class {@value #EXT_LOG_RECORD} and returns
   * its {@code public String getMdc(String)}, or {@code null} when this is not a JBoss LogManager
   * record (by far the common case: plain JUL).
   *
   * <p>Both the declaring class and the method must be {@code public} and the return type must be
   * {@code String}; anything else is refused rather than forced open with {@code setAccessible}.
   */
  private static Method resolve(Class<?> type) {
    for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
      if (!EXT_LOG_RECORD.equals(candidate.getName())) {
        continue;
      }
      if (!Modifier.isPublic(candidate.getModifiers())) {
        // A non-public declaring class could only be invoked after setAccessible; refuse instead.
        return null;
      }
      try {
        Method getMdc = candidate.getMethod("getMdc", String.class);
        boolean usable =
            Modifier.isPublic(getMdc.getModifiers())
                && Modifier.isPublic(getMdc.getDeclaringClass().getModifiers())
                && getMdc.getReturnType() == String.class;
        return usable ? getMdc : null;
      } catch (NoSuchMethodException | RuntimeException e) {
        // An older/newer JBoss LogManager without this exact signature, or a loader that refuses
        // the lookup: the feature degrades to "no MDC", never to a broken logging path.
        return null;
      }
    }
    return null;
  }
}
