package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Lint gate for {@code docs/compatibility.md}: the page makes checkable claims about versions, and
 * nothing else checks them.
 *
 * <p>Two independent kinds of rot are covered, because the page has two independent sources of
 * truth behind it:
 *
 * <ul>
 *   <li><strong>Tested (CI) rows vs. the root {@code pom.xml}.</strong> Those rows say "the exact
 *       version pinned in this repo". A Dependabot bump changes the pin and leaves the sentence
 *       behind, silently making it false — the likeliest way this page decays, since the pins move
 *       on their own schedule.
 *   <li><strong>Verified rows vs. {@code .github/compat-versions.tsv}.</strong> That file drives the
 *       weekly compatibility sweep. Checked in <em>both</em> directions, so the page cannot claim a
 *       version the sweep never runs, and the sweep cannot cover one the page forgot to mention.
 * </ul>
 *
 * <p>This test cannot tell you whether a version actually works — only the sweep can. What it
 * guarantees is that the page and the sweep are talking about the same set of versions, which is the
 * precondition for the sweep's green tick to mean anything about what the page says.
 *
 * <p>It lives in {@code ntfy-core} beside {@link NoGetenvOrXmlGuardTest} because that is where this
 * repository keeps its lint gates, and because ntfy-core is built by every module's {@code -am}
 * reactor — so the check runs no matter which subset of the build someone invokes.
 */
class CompatibilityMatrixGuardTest {

  /** Section heading (matched as a prefix) → the {@code pom.xml} property pinning that component. */
  private static final Map<String, String> SECTION_TO_PROPERTY = new LinkedHashMap<>();

  static {
    SECTION_TO_PROPERTY.put("## Logback", "logback.version");
    SECTION_TO_PROPERTY.put("## Log4j2", "log4j2.version");
    SECTION_TO_PROPERTY.put("## Spring Boot", "spring-boot.version");
    SECTION_TO_PROPERTY.put("## Micronaut", "micronaut.version");
    SECTION_TO_PROPERTY.put("## Quarkus", "quarkus.version");
  }

  private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\b");

  @Test
  void everyPinnedVersionAppearsInItsTestedCiRow() throws IOException {
    Path root = repoRoot();
    String pom = Files.readString(root.resolve("pom.xml"));
    Map<String, List<String>> doc = sections(Files.readString(root.resolve("docs/compatibility.md")));

    List<String> violations = new ArrayList<>();
    SECTION_TO_PROPERTY.forEach((heading, property) -> {
      String pinned = pinnedVersion(pom, property);
      if (pinned == null) {
        violations.add("pom.xml has no <" + property + "> property");
        return;
      }
      List<String> lines = doc.get(heading);
      if (lines == null) {
        violations.add("docs/compatibility.md has no section starting '" + heading + "'");
        return;
      }
      boolean claimed = lines.stream()
          .filter(line -> line.contains("**Tested (CI)**"))
          .anyMatch(line -> line.contains(pinned));
      if (!claimed) {
        violations.add(property + " is pinned to " + pinned
            + " but no '**Tested (CI)**' row under '" + heading + "' mentions it");
      }
    });

    assertThat(violations)
        .as("docs/compatibility.md must name the versions the build actually pins")
        .isEmpty();
  }

  /** The JDK floor is stated in prose rather than a table row, so it is matched literally. */
  @Test
  void documentedMinimumJdkMatchesTheCompilerRelease() throws IOException {
    Path root = repoRoot();
    String release = pinnedVersion(Files.readString(root.resolve("pom.xml")), "maven.compiler.release");
    String doc = Files.readString(root.resolve("docs/compatibility.md"));

    assertThat(release).as("<maven.compiler.release> in pom.xml").isNotNull();
    assertThat(doc)
        .as("docs/compatibility.md must state the same JDK floor the compiler enforces")
        .contains("Minimum supported JDK: **" + release + "**");
  }

  @Test
  void sweptVersionsAndDocumentedVerifiedVersionsAreTheSameSet() throws IOException {
    Path root = repoRoot();
    Map<String, Set<String>> swept = sweptVersionsByProperty(root);
    Map<String, List<String>> doc = sections(Files.readString(root.resolve("docs/compatibility.md")));

    assertThat(swept)
        .as(".github/compat-versions.tsv must not be empty — it drives the whole sweep")
        .isNotEmpty();

    List<String> violations = new ArrayList<>();
    SECTION_TO_PROPERTY.forEach((heading, property) -> {
      Set<String> fromSweep = swept.getOrDefault(property, Set.of());
      Set<String> fromDoc = new LinkedHashSet<>();
      doc.getOrDefault(heading, List.of()).stream()
          .filter(line -> line.contains("**Verified**"))
          .forEach(line -> {
            Matcher m = VERSION.matcher(line);
            while (m.find()) {
              fromDoc.add(m.group());
            }
          });

      Set<String> documentedNotSwept = new TreeSet<>(fromDoc);
      documentedNotSwept.removeAll(fromSweep);
      Set<String> sweptNotDocumented = new TreeSet<>(fromSweep);
      sweptNotDocumented.removeAll(fromDoc);

      if (!documentedNotSwept.isEmpty()) {
        violations.add(heading + ": documented as Verified but never swept: " + documentedNotSwept
            + " (add them to .github/compat-versions.tsv, or stop claiming them)");
      }
      if (!sweptNotDocumented.isEmpty()) {
        violations.add(heading + ": swept but not documented: " + sweptNotDocumented
            + " (add a Verified row, or drop them from .github/compat-versions.tsv)");
      }
    });

    assertThat(violations)
        .as("the compatibility sweep and docs/compatibility.md must cover the same versions")
        .isEmpty();
  }

  /** Every module named in the sweep list must be a real directory with a POM. */
  @Test
  void sweptModulesExist() throws IOException {
    Path root = repoRoot();
    List<String> violations = new ArrayList<>();
    for (String[] row : sweepRows(root)) {
      Path pom = root.resolve(row[0]).resolve("pom.xml");
      if (!Files.isRegularFile(pom)) {
        violations.add("module '" + row[0] + "' in .github/compat-versions.tsv has no pom.xml");
      }
    }
    assertThat(violations).as("sweep modules must resolve to real Maven modules").isEmpty();
  }

  private static Map<String, Set<String>> sweptVersionsByProperty(Path root) throws IOException {
    Map<String, Set<String>> byProperty = new LinkedHashMap<>();
    for (String[] row : sweepRows(root)) {
      byProperty.computeIfAbsent(row[1], k -> new LinkedHashSet<>()).add(row[2]);
    }
    return byProperty;
  }

  /** Parses the sweep list into {@code {module, property, version}} rows, skipping comments. */
  private static List<String[]> sweepRows(Path root) throws IOException {
    List<String[]> rows = new ArrayList<>();
    for (String line : Files.readAllLines(root.resolve(".github/compat-versions.tsv"))) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      String[] parts = trimmed.split("\\s+");
      assertThat(parts)
          .as("malformed row in .github/compat-versions.tsv: '" + line + "'")
          .hasSize(3);
      rows.add(parts);
    }
    return rows;
  }

  /** Splits the page into {@code heading -> lines}, keyed by the {@code ## } headings we care about. */
  private static Map<String, List<String>> sections(String markdown) {
    Map<String, List<String>> sections = new LinkedHashMap<>();
    String current = null;
    for (String line : markdown.lines().toList()) {
      if (line.startsWith("## ")) {
        current = SECTION_TO_PROPERTY.keySet().stream()
            .filter(line::startsWith)
            .findFirst()
            .orElse(null);
        if (current != null) {
          sections.computeIfAbsent(current, k -> new ArrayList<>());
        }
        continue;
      }
      if (current != null) {
        sections.get(current).add(line);
      }
    }
    return sections;
  }

  private static String pinnedVersion(String pom, String property) {
    Matcher m = Pattern.compile("<" + Pattern.quote(property) + ">([^<]+)</").matcher(pom);
    return m.find() ? m.group(1).strip() : null;
  }

  /**
   * Walks up from the module directory to the reactor root, identified by the {@code .github}
   * directory. Surefire's working directory is the module, so nothing here may assume the root.
   */
  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve(".github/workflows"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("reactor root (the ancestor containing .github/workflows)").isNotNull();
    return dir;
  }
}
