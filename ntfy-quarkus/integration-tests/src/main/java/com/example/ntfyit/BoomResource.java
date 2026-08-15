package com.example.ntfyit;

import io.quarkus.logging.Log;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logmanager.MDC;

/**
 * Trivial endpoint whose only job is to emit an ERROR-level log with an exception, so an
 * integration test can prove the ntfy Quarkus extension's installed log handler forwards it to the
 * (loopback) ntfy server. Hitting {@code GET /boom} triggers the alert path end-to-end.
 *
 * <p>Deliberately in {@code com.example.*}, NOT {@code io.github.pimak.ntfy.*}: the engine's
 * always-on self-exclusion drops any log from its own package root to prevent feedback loops, so an
 * app logging from that package would be silently gated out and never alert.
 *
 * <p>It also puts two MDC entries in place around the log call — one allow-listed by {@code
 * quarkus.ntfy.include-mdc-keys}, one not — so the test can assert the real {@code
 * org.jboss.logmanager.ExtLogRecord} MDC is projected into the alert body and that everything the
 * operator did not name stays out of it. {@code org.jboss.logmanager.MDC} is the MDC Quarkus itself
 * funnels every record through, and it comes with the extension's own dependencies — nothing is
 * added to this module for it.
 */
@Path("/boom")
public class BoomResource {

  /** Allow-listed in {@code application.properties}: this value must reach the alert body. */
  private static final String CORRELATION_ID_KEY = "correlation-id";

  /** Deliberately NOT allow-listed: stands in for a secret that must never be published. */
  private static final String SESSION_TOKEN_KEY = "session-token";

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String boom() {
    MDC.put(CORRELATION_ID_KEY, "req-4711");
    MDC.put(SESSION_TOKEN_KEY, "super-secret");
    try {
      // io.quarkus.logging.Log -> JBoss LogManager (JUL) -> the extension's SEVERE handler -> ntfy.
      Log.error("integration boom", new IllegalStateException("kaboom"));
      return "boom";
    } finally {
      // Quarkus serves requests on a pooled worker thread and the MDC is thread-local: leaving
      // these behind would leak this request's context into whatever request the pool hands the
      // thread to next. Always unwind what this method put in place.
      MDC.remove(CORRELATION_ID_KEY);
      MDC.remove(SESSION_TOKEN_KEY);
    }
  }
}
