package io.github.pimak.ntfy.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fails when an {@code ntfy.*} setting this module accepts never reaches the installed appender.
 *
 * <p>{@link NtfyConfiguration} feeds two places: {@code NtfyClientFactory}, which builds the
 * injectable {@code NtfyClient} for notifications, and {@link NtfyLogbackInstaller}, which
 * configures the appender that produces alerts. Wiring one and forgetting the other yields a
 * setting that binds and validates and then does nothing for alerting, with no error to notice —
 * nothing is broken, a value is simply never read.
 *
 * <p>Five settings shipped that way before this test existed. See the sibling guard in the Spring
 * starter for the same check on the same failure.
 */
class PropertyForwardingGuardTest {

  /** Accessors the installer legitimately never reads, each with the reason it does not. */
  private static final Set<String> NOT_THE_APPENDERS_BUSINESS = Set.of("getClass");

  private static Path moduleRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("pom.xml"))
          && p.getFileName().toString().equals("ntfy-micronaut")) {
        return p;
      }
    }
    throw new IllegalStateException("not inside ntfy-micronaut");
  }

  private static String installerSource() throws IOException {
    return Files.readString(
        moduleRoot()
            .resolve("src/main/java/io/github/pimak/ntfy/micronaut/NtfyLogbackInstaller.java"),
        StandardCharsets.UTF_8);
  }

  @Test
  void everySettingReachesTheInstalledAppender() throws IOException {
    String installer = installerSource();
    // A guard reading an empty file would pass forever.
    assertThat(installer).contains("getTopic()");

    List<String> missing = new ArrayList<>();
    for (Method m : NtfyConfiguration.class.getDeclaredMethods()) {
      String n = m.getName();
      boolean reads =
          (n.startsWith("get") || n.startsWith("is"))
              && m.getParameterCount() == 0
              && !NOT_THE_APPENDERS_BUSINESS.contains(n);
      if (reads && !installer.contains(n + "()")) {
        missing.add(n);
      }
    }
    missing.sort(String::compareTo);

    assertThat(missing)
        .as(
            "these ntfy.* settings bind but never reach the appender, so they do nothing for ALERTS"
                + " however carefully they are set. Forward them in NtfyLogbackInstaller, or say in"
                + " NOT_THE_APPENDERS_BUSINESS why one does not belong there.")
        .isEmpty();
  }
}
