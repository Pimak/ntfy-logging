package com.example.ntfycore;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.ConfigLoader;
import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PublishResult;

/**
 * The entry point compiled into a GraalVM native binary by this module's {@code native} profile, so
 * that the hand-rolled (non-Quarkus) native path {@code docs/compatibility.md} documents is measured
 * rather than assumed. {@link CoreNativeBinaryIT} runs the binary against a loopback ntfy stand-in.
 *
 * <p>It is a probe, not a second example: {@link CoreExampleApp} stays the documented one, and every
 * step below delegates to its static methods so the binary exercises exactly the code a reader is
 * shown. What this class adds is the shape a native image needs to be judged on — configuration
 * resolved at image <em>run</em> time, an assertion-free run whose whole verdict is what reaches the
 * wire, and a non-zero exit when a step fails, so a broken image is never a green test.
 *
 * <p>Three reachability mechanisms GraalVM strips in silence are covered, and each one is only
 * observable because it has to cross the network:
 *
 * <ul>
 *   <li>{@link ConfigLoader#load()} reads the ambient environment at run time. An image that folded
 *       it into build-time state would publish to whatever the CI machine had set — that is, to
 *       nothing — and the loopback server would record no request at all.
 *   <li>{@link NtfyClient} opens the JDK {@code HttpClient}. This is the path {@code
 *       native-image.properties} exists for.
 *   <li>{@link AlertEngine}'s shutdown digest is composed from the {@code AlertMessages} {@link
 *       java.util.ResourceBundle}, which {@code resource-config.json} registers for {@code ""},
 *       {@code en} and {@code fr}. Bundle lookup is a separate mechanism from resource loading in a
 *       native image, so a dropped registration surfaces as a digest body reading its own key —
 *       which is why the IT runs this binary twice and compares the two languages.
 * </ul>
 *
 * <p>Every knob comes from the environment ({@code NTFY_URL}, {@code NTFY_TOPIC}, {@code
 * NTFY_LOCALE}, {@code NTFY_MAX_ALERTS_PER_WINDOW}, {@code NTFY_SUPPRESSION_WINDOW}, {@code
 * NTFY_ASYNC}), so the binary needs no arguments and the test needs no rebuild to change a scenario.
 * Note that the language of an alert is a <em>configuration</em> key and never the host JVM's default
 * locale — see {@link NtfyConfig#getLocale()} — so {@code NTFY_LOCALE} is what the second run varies.
 */
public final class CoreNativeProbe {

  /** The engine is disabled, i.e. url/topic were not both set — the harness got the env wrong. */
  private static final int EXIT_NOT_CONFIGURED = 2;

  /** The manual notification did not reach the server — the HTTP path is broken in the image. */
  private static final int EXIT_PUBLISH_FAILED = 3;

  private CoreNativeProbe() {}

  public static void main(String[] args) {
    // Resolved here, at image run time, from the ambient environment — never a build-time constant.
    NtfyConfig config = ConfigLoader.load();
    if (!config.isActive()) {
      System.err.println("probe: ntfy is not configured — set NTFY_URL and NTFY_TOPIC");
      System.exit(EXIT_NOT_CONFIGURED);
    }

    // 1. The manual path: one notification through NtfyClient, synchronous by contract.
    PublishResult result = CoreExampleApp.sendManualNotification(config);
    if (!result.success()) {
      System.err.println("probe: manual notification failed — " + result.message());
      System.exit(EXIT_PUBLISH_FAILED);
    }

    // 2. The alerting path. With max-alerts-per-window=1 the first submission publishes and the
    //    other two are suppressed; the long suppression window means the digest that follows can
    //    only have come from the stop() flush, never from the timer firing on its own.
    AlertEngine engine = new AlertEngine(config, new CoreExampleApp.StderrDiagnostics());
    engine.start();
    for (int i = 0; i < 3; i++) {
      CoreExampleApp.reportCheckoutFailure(engine);
    }
    engine.stop();

    System.out.println(
        "probe: published=" + engine.counters().published()
            + " suppressed=" + engine.counters().suppressed()
            + " failed=" + engine.counters().failed());
  }
}
