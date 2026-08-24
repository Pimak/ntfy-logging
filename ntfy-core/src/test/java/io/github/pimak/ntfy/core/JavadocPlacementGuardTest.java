package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fails when a javadoc block documents nothing, because the block below it opened a new one.
 *
 * <p>This is what happens when a method is inserted ahead of an existing one by matching on its
 * signature: the incoming method lands between the old comment and the method it described, so the
 * old comment silently transfers to the wrong method and the original ships undocumented. Javadoc
 * itself does not complain — it simply keeps the last block and discards the rest — so nothing
 * fails and the wrong prose stays attached until a reader trusts it.
 *
 * <p>It happened four times in one afternoon on this codebase, in four different files, which is
 * how it earned a test rather than a fourth manual correction. The check is deliberately narrow: it
 * only looks for a closed block immediately followed by an opening one, which no legitimate style
 * produces.
 */
class JavadocPlacementGuardTest {

  /**
   * The repository root, found by walking up from wherever the test was launched until a directory
   * holding both {@code pom.xml} and {@code mvnw} appears.
   *
   * <p>Not {@code Path.of("").getParent()}: surefire runs with the module directory as the working
   * directory, but an IDE may run from the repository root instead, and one blind {@code
   * getParent()} would then walk the directory ABOVE the repository — scanning unrelated projects
   * and reporting their files as offenders.
   */
  private static Path repoRoot() {
    for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("pom.xml")) && Files.exists(p.resolve("mvnw"))) {
        return p;
      }
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }

  /**
   * Every Java source file in this repository's own modules.
   *
   * <p>The walk PRUNES rather than filtering afterwards, and that is not only about speed. Build
   * output aside, this repository keeps git worktrees under {@code .claude/} — entire second
   * checkouts, with their own {@code src/} trees at other commits. A filter applied after the fact
   * accepts those and reports offenders in files belonging to another branch, which whoever runs
   * the test cannot fix from where they stand. Skipping every dot-directory and every {@code
   * target/} leaves exactly this checkout's sources.
   */
  private static List<Path> sources() throws IOException {
    List<Path> found = new ArrayList<>();
    Files.walkFileTree(
        repoRoot(),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            return name.startsWith(".") || name.equals("target")
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.toString().endsWith(".java")) {
              found.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return found;
  }

  @Test
  void theScanStaysInsideThisCheckout() throws IOException {
    // The failure this pins is silent: worktrees under .claude/ are entire second checkouts, and
    // scanning one would report offenders in files from another branch that nobody can fix from
    // here. A count is not enough — the paths themselves must contain no pruned segment.
    Path root = repoRoot();
    List<Path> scanned = sources();

    assertThat(scanned).isNotEmpty();
    for (Path source : scanned) {
      assertThat(source).startsWith(root);
      for (Path segment : root.relativize(source)) {
        assertThat(segment.toString())
            .as("scan escaped into %s", source)
            .doesNotStartWith(".")
            .isNotEqualTo("target");
      }
    }
  }

  @Test
  void noJavadocBlockIsOrphanedByAnotherOpeningRightBelowIt() throws IOException {
    List<String> offenders = new ArrayList<>();
    Path root = repoRoot();

    for (Path source : sources()) {
      List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size() - 1; i++) {
        if (lines.get(i).strip().equals("*/") && lines.get(i + 1).strip().equals("/**")) {
          // Path relative to the repository, not just the file name: several modules carry
          // same-named files, and a bare name would leave the reader hunting for which one.
          offenders.add(
              root.relativize(source).toString().replace(java.io.File.separatorChar, '/')
                  + ":"
                  + (i + 1));
        }
      }
    }

    assertThat(offenders)
        .as(
            "a javadoc block ends where the next one begins, so the first documents nothing and the"
                + " member it was written for ships bare. Move the stranded block back onto its own"
                + " member rather than deleting it — the prose is usually the part worth keeping.")
        .isEmpty();
  }
}
