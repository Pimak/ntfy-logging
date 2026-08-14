package io.github.pimak.ntfy.jul;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Programmatic one-liner install of the ntfy JUL handler from the ambient environment: {@code
 * NtfyJulInstaller.install()} early in {@code main} attaches a {@link NtfyJulHandler} to the root
 * logger when the environment configures ntfy ({@code NTFY_URL}/{@code NTFY_TOPIC}, {@code
 * -Dntfy.*}, or a trusted classpath {@code ntfy.properties} — see {@link JulAmbientConfig} for the
 * classpath-endpoint opt-in guard), and does nothing otherwise.
 *
 * <p>To scope alerting to a subtree instead of the whole application, pass that subtree's logger to
 * {@link #install(Logger)} — the same "attach to the specific logger, not root" scoping the other
 * adapters document. For fully declarative installation (no code at all, e.g. Tomcat's {@code
 * logging.properties}) use {@link NtfyJulAutoHandler} instead.
 *
 * <p>The returned handler owns its engine: call {@link #uninstall(Logger, NtfyJulHandler)} (or
 * {@code removeHandler} + {@link NtfyJulHandler#close()}) on shutdown to flush the async queue and
 * release the HTTP client. JUL's {@code LogManager} shutdown hook also closes attached handlers on
 * normal JVM exit.
 */
public final class NtfyJulInstaller {

  private NtfyJulInstaller() {}

  /** Installs on the root logger. See {@link #install(Logger)}. */
  public static Optional<NtfyJulHandler> install() {
    return install(Logger.getLogger(""));
  }

  /**
   * Resolves the ambient config and, when active and vetted, attaches a started {@link
   * NtfyJulHandler} to {@code logger}. Empty — with nothing attached — when ntfy is not configured,
   * the classpath-endpoint guard refused, or resolution/construction failed (reported on {@code
   * System.err}); like the auto-handler, this entry point never lets an alerting-setup failure
   * propagate into the caller.
   */
  public static Optional<NtfyJulHandler> install(Logger logger) {
    Objects.requireNonNull(logger, "logger");
    JulDiagnostics diagnostics = new JulDiagnostics();
    try {
      Optional<NtfyJulHandler> handler =
          JulAmbientConfig.resolve(diagnostics).map(NtfyJulHandler::forConfig);
      handler.ifPresent(logger::addHandler);
      return handler;
    } catch (RuntimeException e) {
      // Same defense as NtfyJulAutoHandler: a failure to read ambient config (e.g. a
      // SecurityException from the env/sysprop lookups) degrades to "not installed", never a
      // throw out of a one-liner documented as always safe to call.
      diagnostics.error("ntfy: installer initialization failed", e);
      return Optional.empty();
    }
  }

  /** Detaches {@code handler} from {@code logger} and stops its engine. */
  public static void uninstall(Logger logger, NtfyJulHandler handler) {
    Objects.requireNonNull(logger, "logger");
    Objects.requireNonNull(handler, "handler");
    logger.removeHandler(handler);
    handler.close();
  }
}
