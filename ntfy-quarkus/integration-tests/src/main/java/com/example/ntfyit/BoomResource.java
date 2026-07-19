package com.example.ntfyit;

import io.quarkus.logging.Log;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Trivial endpoint whose only job is to emit an ERROR-level log with an exception, so an
 * integration test can prove the ntfy Quarkus extension's installed log handler forwards it to the
 * (loopback) ntfy server. Hitting {@code GET /boom} triggers the alert path end-to-end.
 *
 * <p>Deliberately in {@code com.example.*}, NOT {@code io.github.pimak.ntfy.*}: the engine's
 * always-on self-exclusion drops any log from its own package root to prevent feedback loops, so an
 * app logging from that package would be silently gated out and never alert.
 */
@Path("/boom")
public class BoomResource {

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String boom() {
    // io.quarkus.logging.Log -> JBoss LogManager (JUL) -> the extension's SEVERE handler -> ntfy.
    Log.error("integration boom", new IllegalStateException("kaboom"));
    return "boom";
  }
}
