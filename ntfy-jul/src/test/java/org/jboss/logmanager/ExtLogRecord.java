/*
 * TEST-ONLY STUB — DO NOT "FIX" THIS BY ADDING THE REAL jboss-logmanager DEPENDENCY.
 *
 * ntfy-jul's promise is "ntfy-core + the JDK's java.util.logging, nothing else", enforced by the
 * bannedDependencies ALLOW-LIST in this module's pom (searchTransitive=true). Adding
 * org.jboss.logmanager:jboss-logmanager — even in test scope — fails `mvn validate`. Because that
 * artifact is on NO classpath of this module, this file shadows nothing: it is simply the only
 * org.jboss.logmanager.ExtLogRecord that exists here.
 *
 * That is enough, because ExtLogRecordMdc reaches the MDC purely by reflection on a fully qualified
 * NAME. This stub therefore exercises the entire real reflective path end to end — the exact-FQCN
 * match, the superclass walk, Class#getMethod, the public/return-type checks, and Method#invoke —
 * with the same code that runs in production. Only the class BODY is a stand-in.
 *
 * Coverage against the real JBoss LogManager API lives in the Quarkus integration tests, where the
 * genuine artifact is on the classpath and a real ExtLogRecord flows through the handler.
 */
package org.jboss.logmanager;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Minimal stand-in for JBoss LogManager's {@code ExtLogRecord}: a public {@link LogRecord} subclass
 * with a test-settable MDC, a {@code public String getMdc(String)} matching the real signature, and
 * a call counter so a test can assert that the MDC was NEVER consulted (the "feature unset costs
 * nothing" guarantee).
 */
public class ExtLogRecord extends LogRecord {

  private static final long serialVersionUID = 1L;

  private final transient Map<String, String> mdc = new HashMap<>();

  /** Counts every {@link #getMdc} call, including ones that end up throwing. */
  private transient int mdcCallCount;

  /** When set, {@link #getMdc} throws it — models a hostile/broken MDC implementation. */
  private transient RuntimeException mdcFailure;

  public ExtLogRecord(Level level, String msg) {
    super(level, msg);
  }

  /** Adds one MDC entry; returns {@code this} so records can be built in one expression. */
  public ExtLogRecord withMdc(String key, String value) {
    mdc.put(key, value);
    return this;
  }

  /** Makes every subsequent {@link #getMdc} call throw {@code failure}. */
  public ExtLogRecord failingWith(RuntimeException failure) {
    this.mdcFailure = failure;
    return this;
  }

  /** How many times the MDC was consulted; {@code 0} proves the allow-list short-circuit held. */
  public int mdcCallCount() {
    return mdcCallCount;
  }

  /** The real API's per-key accessor — the exact method {@code ExtLogRecordMdc} reflects on. */
  public String getMdc(String key) {
    mdcCallCount++;
    if (mdcFailure != null) {
      throw mdcFailure;
    }
    return mdc.get(key);
  }
}
