package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Lint gate for the reproducible-build stamp: {@code <project.build.outputTimestamp>} in the root
 * {@code pom.xml}.
 *
 * <p>That one property is what makes every jar in the reactor byte-identical when rebuilt from the
 * same sources — it replaces the wall-clock entries the archiver would otherwise write into each
 * zip. It extends to the whole reactor what {@code <notimestamp>} already does for the javadoc.
 *
 * <p>It has a failure mode the javadoc setting does not, and that is what this class exists for: it
 * is a <em>date</em>, so it goes stale. A stamp left on the previous release's date still produces
 * reproducible jars — every rebuild agrees — but they claim a build date that is not the release's,
 * which is exactly the claim a verifier rebuilding from the tag checks. So the checks below tie it
 * to something that moves on its own: the CHANGELOG date that {@code release.sh} stamps from the
 * same {@code TODAY} in the same run.
 */
class ReproducibleBuildGuardTest {

  private static final String PROPERTY = "project.build.outputTimestamp";

  /** {@code ## [2.0.0] - 2026-08-20} — the newest of these is the release the tree describes. */
  private static final Pattern DATED_RELEASE =
      Pattern.compile("^## \\[\\d+\\.\\d+\\.\\d+]\\s*-\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$");

  @Test
  void theReactorDeclaresAnOutputTimestamp() throws IOException {
    Path root = repoRoot();
    String stamp = outputTimestamp(Files.readString(root.resolve("pom.xml")));

    assertThat(stamp)
        .as("the root pom.xml must set <" + PROPERTY + "> — without it every rebuild writes fresh"
            + " archive timestamps and no two builds of the same source agree")
        .isNotNull();
  }

  @Test
  void theOutputTimestampIsAnIsoInstantMavenWillAccept() throws IOException {
    Path root = repoRoot();
    String stamp = outputTimestamp(Files.readString(root.resolve("pom.xml")));
    assertThat(stamp).as("<" + PROPERTY + "> in pom.xml").isNotNull();

    // Maven accepts either an epoch-seconds integer or an ISO-8601 instant, and silently ignores a
    // value it cannot parse — a build that looks reproducible and is not. This project writes the
    // ISO form, deliberately and not merely by habit: release.sh derives it from the same ${TODAY}
    // that dates the CHANGELOG, and the check below compares the two. An epoch integer would be
    // just as reproducible and completely opaque to that comparison, so it is rejected here rather
    // than silently unguarded.
    assertThatCode(() -> OffsetDateTime.parse(stamp))
        .as("<" + PROPERTY + "> is '" + stamp + "', which is not the ISO-8601 instant this project"
            + " writes — Maven ignores what it cannot parse, so a malformed value would drop"
            + " reproducibility without failing anything")
        .doesNotThrowAnyException();
  }

  /**
   * The stamp and the CHANGELOG's newest dated section both come out of {@code release.sh}'s single
   * {@code TODAY}, so on a released tree they agree by construction. Where they disagree, someone
   * edited one by hand — or the stamp was never wired into the release at all, which is the whole
   * failure this guards.
   */
  @Test
  void theOutputTimestampMatchesTheNewestReleaseDate() throws IOException {
    Path root = repoRoot();
    String stamp = outputTimestamp(Files.readString(root.resolve("pom.xml")));
    String released = newestReleaseDate(root);

    assertThat(stamp).as("<" + PROPERTY + "> in pom.xml").isNotNull();
    assertThat(released).as("a dated '## [x.y.z] - <date>' section in CHANGELOG.md").isNotNull();
    assertThat(stamp)
        .as("<" + PROPERTY + "> must carry the date of the newest CHANGELOG release — release.sh"
            + " stamps both from the same TODAY, so a mismatch means the stamp went stale")
        .startsWith(released);
  }

  /**
   * The stamp only stays true if every release moves it, and only {@code release.sh} moves anything
   * at release time.
   *
   * <p>Checked against the script's <em>code</em>, with comments stripped. Asserting merely that the
   * file mentions the property would be satisfied by the paragraph that explains the property —
   * delete the substitution, keep the explanation, and a check written that way stays green while
   * describing something the script no longer does.
   */
  @Test
  void releaseScriptStampsTheOutputTimestamp() throws IOException {
    Path root = repoRoot();
    List<String> code = Files.readAllLines(root.resolve("release.sh")).stream()
        .filter(line -> !line.strip().startsWith("#"))
        .toList();

    assertThat(code)
        .as("release.sh must contain a substitution that rewrites <" + PROPERTY + "> — a stamp"
            + " nothing updates is a date that silently describes the previous release forever")
        .anyMatch(line -> line.startsWith("sed") && line.contains(PROPERTY));

    // The substitution alone is not enough, and this project already learned why: sed is line-based,
    // so a property reformatted across lines leaves it matching nothing while the script carries on
    // to commit and tag. Whatever shape it takes, something after the rewrite must read the file
    // back and compare against the date being released.
    assertThat(code)
        .as("release.sh must verify the rewrite landed, not merely attempt it")
        .anyMatch(line -> line.contains(PROPERTY) && line.contains("${TODAY}")
            && !line.startsWith("sed"));
  }

  private static String outputTimestamp(String pom) {
    Matcher m = Pattern.compile("<" + Pattern.quote(PROPERTY) + ">([^<]*)</").matcher(pom);
    return m.find() ? m.group(1).strip() : null;
  }

  /** The date of the topmost {@code ## [x.y.z] - <date>} heading; the CHANGELOG is newest-first. */
  private static String newestReleaseDate(Path root) throws IOException {
    for (String line : Files.readAllLines(root.resolve("CHANGELOG.md"))) {
      Matcher m = DATED_RELEASE.matcher(line.strip());
      if (m.matches()) {
        return m.group(1);
      }
    }
    return null;
  }

  /**
   * Walks up from the module directory to the reactor root, identified by the {@code
   * .github/workflows} directory. Surefire's working directory is the module, so nothing here may
   * assume the root. The marker is the workflows directory rather than {@code .github} itself
   * because {@code .github} is a name any nested directory may carry; loosening it to the parent
   * would let the walk stop early.
   *
   * <p>Carried here rather than shared, matching {@link CompatibilityMatrixGuardTest}, which holds
   * its own copy: each lint gate stands alone, so one can be moved or dropped without disturbing its
   * neighbours. Four lines of walking is a cheaper duplication than a coupling between gates.
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
