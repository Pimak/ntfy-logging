package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where {@link NativeImageMetadataGuardTest} finds the repository it is checking.
 *
 * <p>Extracted for the module walk rather than for the root lookup: locating the reactor root is
 * four lines that {@link CompatibilityMatrixGuardTest} also carries privately, but enumerating the
 * declared modules and the metadata they ship is neither short nor obvious to get right, and the
 * gate's correctness depends on it entirely — see {@link #modules(Path)}.
 */
final class ReactorLayout {

  private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

  private ReactorLayout() {}

  /**
   * Walks up from the module directory to the reactor root, identified by the {@code
   * .github/workflows} directory. Surefire's working directory is the module, so nothing here may
   * assume the root.
   *
   * <p>The marker is the workflows directory rather than {@code .github} itself, and that is not
   * incidental: {@code .github} is a name any nested directory may carry, while a reactor with no
   * workflows is not this repository. Loosening it to the parent would let the walk stop early.
   */
  static Path root() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve(".github/workflows"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("reactor root (the ancestor containing .github/workflows)").isNotNull();
    return dir;
  }

  /**
   * Every module of the reactor, aggregators included, found by following {@code <module>} from the
   * root pom.
   *
   * <p>Declared modules rather than "every directory holding a pom.xml", and that distinction is the
   * point of this method. A working tree can hold whole copies of the repository inside ignored
   * directories — a linked worktree, a scratch checkout, an unpacked archive — each carrying the
   * same modules, the same resources and the same native-image metadata. A gate that discovered its
   * own inputs by walking the filesystem would read those copies too, so it would fail on whatever
   * happened to be checked out beside it, on one machine only, and stay green in CI where nothing
   * else is. Following the reactor cannot wander out of it.
   */
  static List<Path> modules(Path root) throws IOException {
    Set<Path> found = new LinkedHashSet<>();
    collectModules(root, found);
    return List.copyOf(found);
  }

  private static void collectModules(Path module, Set<Path> found) throws IOException {
    Path pom = module.resolve("pom.xml");
    if (!Files.isRegularFile(pom) || !found.add(module)) {
      return;
    }
    Matcher m = MODULE.matcher(Files.readString(pom));
    while (m.find()) {
      collectModules(module.resolve(m.group(1).strip()).normalize(), found);
    }
  }

  /**
   * Every file of the given name under {@code src/main/resources/META-INF/native-image} in any
   * reactor module — the metadata GraalVM picks up automatically from the published jars.
   */
  static List<Path> nativeImageMetadata(Path root, String fileName) throws IOException {
    return nativeImageMetadata(root).stream()
        .filter(p -> p.getFileName().toString().equals(fileName))
        .toList();
  }

  /**
   * Every native-image metadata file the reactor ships, whatever it is called.
   *
   * <p>The name-agnostic listing is what lets a gate check an inventory rather than a checklist: a
   * check written against known filenames can only ever confirm the files someone thought to name,
   * and stays silent about a new one appearing beside them.
   */
  static List<Path> nativeImageMetadata(Path root) throws IOException {
    List<Path> found = new ArrayList<>();
    for (Path module : modules(root)) {
      Path dir = module.resolve("src/main/resources/META-INF/native-image");
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var walk = Files.walk(dir)) {
        walk.filter(Files::isRegularFile).sorted().forEach(found::add);
      }
    }
    return found;
  }

  /** The reactor module a metadata file belongs to — the ancestor holding its {@code src}. */
  static Path owningModule(Path metadataFile) {
    for (Path dir = metadataFile.getParent(); dir != null; dir = dir.getParent()) {
      if (dir.endsWith(Path.of("src/main/resources"))) {
        return dir.getParent().getParent().getParent();
      }
    }
    throw new IllegalArgumentException(metadataFile + " is not under a src/main/resources");
  }
}
