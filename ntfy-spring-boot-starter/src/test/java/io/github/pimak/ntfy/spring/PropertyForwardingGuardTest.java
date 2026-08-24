package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails when a property this starter accepts is not handed on to the appender that produces alerts.
 *
 * <p>The starter has two consumers of {@link NtfyProperties}: the injected {@code NtfyClient} bean,
 * which sends notifications, and the auto-installed appender, which sends alerts. Adding a property
 * means wiring it into both, and forgetting the second one produces a setting that binds, validates,
 * appears in the metadata, and then does nothing at all for alerting — with no error anywhere,
 * because nothing is wrong except that a value is never read.
 *
 * <p>That is not hypothetical. Five properties shipped in this state before this test existed:
 * {@code excluded-exception-types}, {@code cache}, {@code firebase}, {@code icon} and {@code
 * icons-by-logger} all reached the client bean and none reached the appender, so a deny-list an
 * operator configured filtered nothing.
 *
 * <p>The check is on the SOURCE rather than on behaviour, deliberately: it is the wiring that goes
 * missing, and a behavioural test only catches the property someone thought to write one for.
 */
class PropertyForwardingGuardTest {

  /**
   * Properties the appender legitimately never sees, each for a reason that is not "we forgot".
   * Keep this list short and keep the reason with it.
   */
  private static final Set<String> NOT_THE_APPENDERS_BUSINESS =
      Set.of(
          // Read by NtfyAutoConfiguration itself to decide whether to install at all, and passed to
          // the appender as `enabled` only through that decision.
          "getClass");

  private static Path moduleRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("pom.xml"))
          && p.getFileName().toString().equals("ntfy-spring-boot-starter")) {
        return p;
      }
    }
    throw new IllegalStateException("not inside ntfy-spring-boot-starter");
  }

  private static String sourceOf(String simpleName) throws IOException {
    Path file =
        moduleRoot()
            .resolve("src/main/java/io/github/pimak/ntfy/spring")
            .resolve(simpleName + ".java");
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  /** Every readable property on {@link NtfyProperties}, by accessor name. */
  private static List<String> accessors() {
    List<String> names = new ArrayList<>();
    for (Method m : NtfyProperties.class.getDeclaredMethods()) {
      String n = m.getName();
      boolean reads =
          (n.startsWith("get") || n.startsWith("is"))
              && m.getParameterCount() == 0
              && !NOT_THE_APPENDERS_BUSINESS.contains(n);
      if (reads) {
        names.add(n);
      }
    }
    return names;
  }

  @Test
  void everyPropertyReachesBothAppenderBackends() throws IOException {
    String logback = sourceOf("LogbackNtfyBackend");
    String log4j2 = sourceOf("Log4j2NtfyBackend");

    TreeMap<String, List<String>> missing = new TreeMap<>();
    for (String accessor : accessors()) {
      List<String> absentFrom = new ArrayList<>();
      if (!logback.contains(accessor + "()")) {
        absentFrom.add("LogbackNtfyBackend");
      }
      if (!log4j2.contains(accessor + "()")) {
        absentFrom.add("Log4j2NtfyBackend");
      }
      if (!absentFrom.isEmpty()) {
        missing.put(accessor, absentFrom);
      }
    }

    assertThat(missing)
        .as(
            "these ntfy.* properties bind and validate but are never handed to the appender, so they"
                + " do nothing for ALERTS however carefully an operator sets them. Forward them in"
                + " the backend(s) named, or — if one genuinely does not belong there — say so in"
                + " NOT_THE_APPENDERS_BUSINESS with the reason.")
        .isEmpty();
  }

  @Test
  void theGuardIsLookingAtSourceItCanActuallyRead() throws IOException {
    // A guard that silently reads nothing passes forever. Pin that both files exist and that a
    // property known to be forwarded is visible to the matcher.
    assertThat(Stream.of("LogbackNtfyBackend", "Log4j2NtfyBackend"))
        .allSatisfy(
            name -> {
              String source = sourceOf(name);
              assertThat(source).isNotBlank();
              assertThat(source).contains("getTopic()");
            });
  }
}
