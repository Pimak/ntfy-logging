package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Lint gate: environment access in ntfy-core must stay funnelled through the single sanctioned
 * reader, {@link ConfigLoader}. Every OTHER main-source class must be configured through an
 * already-built {@link NtfyConfig}, never by reading the process environment directly — a scattered
 * {@code System.getenv} elsewhere would reintroduce exactly the hidden config coupling this module
 * is designed to avoid. The module must also ship no logging XML of its own (framework wiring is the
 * consumer/adapter's concern).
 */
class NoGetenvOrXmlGuardTest {

  private static final Pattern GETENV_CALL = Pattern.compile("System\\.getenv\\(");

  /** The one class allowed to read the environment — the sanctioned single config reader. */
  private static final String SANCTIONED_ENV_READER = "ConfigLoader.java";

  @Test
  void noGetenvCallOutsideConfigLoader() throws IOException {
    Path srcMain = Path.of("").toAbsolutePath().resolve("src/main/java");
    assertThat(srcMain).as("src/main/java must exist").isDirectory();

    List<String> violations = new ArrayList<>();
    try (Stream<Path> javaFiles = Files.walk(srcMain)) {
      javaFiles
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().equals(SANCTIONED_ENV_READER))
          .forEach(
              path -> {
                try {
                  List<String> lines = Files.readAllLines(path);
                  for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = GETENV_CALL.matcher(lines.get(i));
                    if (matcher.find()) {
                      violations.add(path.toAbsolutePath() + ":" + (i + 1));
                    }
                  }
                } catch (IOException e) {
                  throw new RuntimeException("Failed to read " + path, e);
                }
              });
    }

    assertThat(violations)
        .as("System.getenv() is only allowed in " + SANCTIONED_ENV_READER)
        .isEmpty();
  }

  @Test
  void noXmlShippedInMainResources() throws IOException {
    Path resourcesDir = Path.of("").toAbsolutePath().resolve("src/main/resources");
    if (!Files.isDirectory(resourcesDir)) {
      // No resources directory at all is a valid pass — this module ships no XML of its own.
      return;
    }

    try (Stream<Path> files = Files.walk(resourcesDir)) {
      List<String> xmlFiles =
          files.filter(p -> p.toString().endsWith(".xml")).map(Path::toString).toList();
      assertThat(xmlFiles).as("ntfy-core ships no XML of its own").isEmpty();
    }
  }
}
