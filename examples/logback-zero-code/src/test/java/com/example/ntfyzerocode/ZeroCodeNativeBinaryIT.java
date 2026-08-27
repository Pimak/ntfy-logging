package com.example.ntfyzerocode;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the GraalVM native binary built from {@link ZeroCodeNativeProbe} and answers the question
 * this module was created for: does Logback's {@code Configurator} service lookup survive image
 * build when the jar behind it ships no reachability metadata?
 *
 * <p>The assertion is two-sided on purpose. The binary's own exit status reports whether the
 * appender was installed — observed from inside the image, which is the only place that fact exists
 * — and the loopback server reports whether it then published. A service silently dropped by
 * native-image fails the first; an appender installed but unable to reach the network fails the
 * second. Collapsing them into one would leave a broken image able to look healthy.
 *
 * <p>Tagged {@code native-binary}: outside the {@code native} profile the binary does not exist, and
 * a missing binary must fail this test rather than skip it.
 */
@Tag("native-binary")
class ZeroCodeNativeBinaryIT {

  /** Set by failsafe in the {@code native} profile; see this module's pom. */
  private static final Path BINARY = Path.of(System.getProperty("ntfy.native.probe", "unset"));

  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

  /** How long a forcibly killed probe gets to actually die before its output is read. */
  private static final Duration KILL_TIMEOUT = Duration.ofSeconds(5);

  @Test
  void theZeroCodeAutoInstallSurvivesImageBuild() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      String output = run(server);

      assertThat(output)
          .as("the probe prints the root logger's appenders — output was:%n%s", output)
          .contains(ZeroCodeNativeProbe.AUTO_APPENDER_NAME);

      LoopbackNtfyServer.ReceivedRequest alert = server.awaitRequest(0, Duration.ofSeconds(5));
      assertThat(alert.path()).isEqualTo("/alerts");
      assertThat(alert.body()).contains("row 42: malformed record");
      assertThat(server.received())
          .as("no logback.xml in the image, so exactly one appender can publish")
          .hasSize(1);
    }
  }

  /**
   * Output goes to a file rather than through a pipe this method reads. Reading the pipe first would
   * block until the child closes it — normally at exit, but not if it hangs — and {@link
   * RUN_TIMEOUT} below would then never be reached, leaving nothing to stop the run but the CI job's
   * own hour. A file also survives {@link Process#destroyForcibly()}, so a killed probe is still
   * quoted in the failure rather than reported as silence.
   */
  private static String run(LoopbackNtfyServer server) throws Exception {
    assertThat(BINARY)
        .as("the native profile must build the probe and hand its path to failsafe")
        .exists();

    Path outputFile = Files.createTempFile("ntfy-logback-zero-code-probe-", ".out");
    try {
      ProcessBuilder builder =
          new ProcessBuilder(BINARY.toString())
              .redirectErrorStream(true)
              .redirectOutput(outputFile.toFile());
      Map<String, String> env = builder.environment();
      env.put("NTFY_URL", server.url());
      env.put("NTFY_TOPIC", "alerts");
      env.put("NTFY_ASYNC", "false");

      Process process = builder.start();
      boolean exited = process.waitFor(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      }
      // readAllBytes + new String, never Files.readString: the latter THROWS on a malformed
      // byte, and the one job this capture has is to be quotable when something went wrong. A
      // probe that died mid-write, or wrote in another encoding, must still be readable here.
      String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);

      assertThat(exited)
          .as("the probe did not exit within %s — output so far was:%n%s", RUN_TIMEOUT, output)
          .isTrue();
      assertThat(process.exitValue())
          .as("exit 4 means the SPI installed no appender — output was:%n%s", output)
          .isZero();
      return output;
    } finally {
      Files.deleteIfExists(outputFile);
    }
  }
}
