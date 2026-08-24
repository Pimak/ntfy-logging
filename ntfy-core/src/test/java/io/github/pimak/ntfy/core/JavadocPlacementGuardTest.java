package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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

  /** Every Java source file in this repository's published modules. */
  private static List<Path> sources() throws IOException {
    Path repoRoot = Path.of("").toAbsolutePath().getParent();
    try (Stream<Path> tree = Files.walk(repoRoot)) {
      return tree.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> p.toString().replace('\\', '/').contains("/src/"))
          .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
          .toList();
    }
  }

  @Test
  void noJavadocBlockIsOrphanedByAnotherOpeningRightBelowIt() throws IOException {
    List<String> offenders = new ArrayList<>();

    for (Path source : sources()) {
      List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size() - 1; i++) {
        if (lines.get(i).strip().equals("*/") && lines.get(i + 1).strip().equals("/**")) {
          offenders.add(source.getFileName() + ":" + (i + 1));
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
