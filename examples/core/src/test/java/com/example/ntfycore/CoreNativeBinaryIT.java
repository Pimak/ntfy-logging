package com.example.ntfycore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the GraalVM native binary built from {@link CoreNativeProbe} against a loopback ntfy
 * stand-in, which is what turns the hand-rolled (non-Quarkus) native path from a documented claim
 * into a measured one. Everything asserted here is observed over the wire, from outside the process:
 * a native image that quietly lost a registration cannot report itself healthy.
 *
 * <p>Tagged {@code native-binary} and excluded from the default build — the binary only exists once
 * the {@code native} profile has compiled it, and the CI {@code native-smoke} job is the only place
 * that happens. The exclusion is by tag rather than by an {@code assumeTrue} on the binary's
 * existence on purpose: a missing binary must fail this test, not skip it green.
 *
 * <p>The two runs differ in one environment variable, {@code NTFY_LOCALE}. That is the whole point:
 * the shutdown digest is composed from the {@code AlertMessages} {@link java.util.ResourceBundle},
 * so running the same binary twice and getting two languages proves the {@code fr} bundle survived
 * into the image. GraalVM prunes bundles it cannot prove reachable, and the failure is silent — a
 * dropped {@code fr} falls back to the English base and reads perfectly well.
 */
@Tag("native-binary")
class CoreNativeBinaryIT {

  /** Set by failsafe in the {@code native} profile; see this module's pom. */
  private static final Path BINARY = Path.of(System.getProperty("ntfy.native.probe", "unset"));

  /** Generous: a native binary starts in milliseconds, but a loaded CI runner is not the metric. */
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

  /** Long enough that the digest below can only come from the stop() flush, never from the timer. */
  private static final String SUPPRESSION_WINDOW_MILLIS = "600000";

  @Test
  void theBinaryPublishesAndDigestsInEnglish() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      String output = run(server, "en");

      assertThat(server.received()).as("output was:%n%s", output).hasSize(3);

      LoopbackNtfyServer.ReceivedRequest manual = server.received().get(0);
      assertThat(manual.path()).isEqualTo("/alerts");
      assertThat(manual.body()).contains("12,304 rows processed");

      LoopbackNtfyServer.ReceivedRequest digest = server.received().get(2);
      assertThat(digest.header("Priority")).isEqualTo("urgent");
      assertThat(digest.body()).contains("2 errors suppressed");

      assertThat(output).contains("published=2 suppressed=2 failed=0");
    }
  }

  @Test
  void theFrenchBundleSurvivesIntoTheImage() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      String output = run(server, "fr");

      LoopbackNtfyServer.ReceivedRequest digest = server.received().get(2);
      assertThat(digest.body())
          .as("a pruned fr bundle falls back to the English base, silently — output was:%n%s", output)
          .contains("2 erreurs supprimées")
          .doesNotContain("errors suppressed");
    }
  }

  /**
   * Runs the binary to completion against {@code server} and returns its merged stdout/stderr, which
   * every assertion above quotes on failure: when a native image breaks, the reason is usually in
   * the engine's own diagnostics rather than in what did or did not reach the wire.
   */
  private static String run(LoopbackNtfyServer server, String localeTag) throws Exception {
    assertThat(BINARY)
        .as("the native profile must build the probe and hand its path to failsafe")
        .exists();

    ProcessBuilder builder = new ProcessBuilder(BINARY.toString()).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put("NTFY_URL", server.url());
    env.put("NTFY_TOPIC", "alerts");
    env.put("NTFY_APP_NAME", "core-example");
    env.put("NTFY_LOCALE", localeTag);
    env.put("NTFY_MAX_ALERTS_PER_WINDOW", "1");
    env.put("NTFY_SUPPRESSION_WINDOW", SUPPRESSION_WINDOW_MILLIS);
    // Synchronous delivery: the probe's publishes are then done when its process exits, so the
    // assertions need no polling and a missing request is a real failure rather than a race.
    env.put("NTFY_ASYNC", "false");

    Process process = builder.start();
    String output = readFully(process.getInputStream());
    boolean exited = process.waitFor(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    if (!exited) {
      process.destroyForcibly();
    }
    assertThat(exited).as("the probe did not exit within %s — output was:%n%s", RUN_TIMEOUT, output)
        .isTrue();
    assertThat(process.exitValue()).as("probe output was:%n%s", output).isZero();
    return output;
  }

  private static String readFully(InputStream stream) throws IOException {
    try (InputStream in = stream) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
