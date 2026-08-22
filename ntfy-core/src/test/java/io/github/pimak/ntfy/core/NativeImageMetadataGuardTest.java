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
 * Lint gate for the GraalVM section of {@code docs/compatibility.md}: that section is the only
 * description anyone gets of the native-image metadata the jars actually ship, and nothing else
 * checks that the description is complete.
 *
 * <p>It went stale once already — the page named the {@code --enable-url-protocols=https} flag and
 * the {@code ntfy.properties} resource, and said nothing about the {@code bundles} block that keeps
 * the localized alert messages readable in a native image. A reader following that page would have
 * concluded the bundle registration was missing and added it again by hand.
 *
 * <p>So the direction of most checks here is metadata → page: whatever the modules ship must be
 * named on the page. The reverse is deliberately not enforced, because the page also documents the
 * Quarkus path, which needs none of this metadata, and the native paths it does <em>not</em> claim.
 * The exceptions are {@link #everyTranslationOnDiskIsRegisteredForNativeImage()} and {@link
 * #everyResourceReadOrShippedIsRegisteredForNativeImage()}, which compare two build inputs to each
 * other and never read the page at all. Those two are also the only checks here that survive an
 * entry being <em>deleted</em>: a metadata → page check passes once the entry is gone, having
 * nothing left to disagree with, while the page carries on promising it.
 *
 * <p>What "whatever the modules ship" covers, precisely, because a gate that overstates its own
 * reach is worse than one that admits a gap — the reader stops looking:
 *
 * <ul>
 *   <li>the <strong>set of metadata files</strong> itself, so the page's claim to inventory them
 *       cannot be falsified by adding a fifth one;
 *   <li>every <strong>resource</strong> registered for inclusion, matched on the literal part of
 *       its pattern;
 *   <li>every <strong>resource bundle</strong> and each <strong>locale</strong> registered for it;
 *   <li>every <strong>type registered for reflection</strong>;
 *   <li>every <strong>flag</strong> passed to the image builder.
 * </ul>
 *
 * <p>That is the whole of the metadata's content. What is deliberately <em>not</em> checked is the
 * page's prose <em>about</em> each entry — that the https handler is stripped without the flag, that
 * a bundle falls back to its key — which is explanation, not data, and has no machine-readable
 * counterpart to compare against.
 *
 * <p>Lives in {@code ntfy-core} beside {@link CompatibilityMatrixGuardTest} and {@link
 * NoGetenvOrXmlGuardTest} for the same reason they do: that is where this repository keeps its lint
 * gates, and ntfy-core is in every module's {@code -am} reactor, so the check runs whichever subset
 * of the build someone invokes.
 */
class NativeImageMetadataGuardTest {

  /** Heading of the section that carries the hand-rolled native-image story. */
  private static final String GRAALVM_SECTION = "## GraalVM native image";

  /** A {@code "key": "value"} pair, used to read one field of a bundle entry. */
  private static final Pattern STRING_FIELD =
      Pattern.compile("\"([A-Za-z]+)\"\\s*:\\s*\"([^\"]*)\"");

  /** A {@code "locales": [ … ]} array, captured whole. */
  private static final Pattern LOCALES_FIELD =
      Pattern.compile("\"locales\"\\s*:\\s*\\[([^]]*)]");

  /** Regex constructs that end the literal head of an inclusion pattern. */
  private static final String REGEX_META = "([{*+?|^$.";

  /** A {@code getResource}/{@code getResourceAsStream} call, capturing its argument. */
  private static final Pattern RESOURCE_CALL =
      Pattern.compile("getResource(?:AsStream)?\\(\\s*(\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

  /** A quoted string inside a {@code locales} array. */
  private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

  @Test
  void everyRegisteredResourceBundleIsNamedOnThePage() throws IOException {
    Path root = ReactorLayout.root();
    String section = graalvmSection(root);

    List<String> violations = new ArrayList<>();
    for (Path config : resourceConfigs(root)) {
      for (String bundle : bundles(Files.readString(config)).keySet()) {
        if (!section.contains(bundle)) {
          violations.add(root.relativize(config) + " registers the resource bundle '" + bundle
              + "', which '" + GRAALVM_SECTION + "' of docs/compatibility.md never mentions");
        }
      }
    }

    assertThat(violations)
        .as("docs/compatibility.md must describe every resource bundle the jars register")
        .isEmpty();
  }

  /**
   * Every flag the shipped {@code native-image.properties} passes to the image builder must be on
   * the page. A flag is a promise about how the binary behaves — {@code --enable-url-protocols} is
   * the difference between a working publish and {@code unknown protocol: https} — and an
   * undocumented one leaves a reader no way to tell what the jar already does for them.
   */
  @Test
  void everyNativeImageArgIsNamedOnThePage() throws IOException {
    Path root = ReactorLayout.root();
    String section = graalvmSection(root);

    List<Path> configs = ReactorLayout.nativeImageMetadata(root, "native-image.properties");
    assertThat(configs)
        .as("no native-image.properties found in any reactor module — this gate reads the shipped"
            + " metadata, so finding none means it is checking nothing at all")
        .isNotEmpty();

    List<String> violations = new ArrayList<>();
    for (Path config : configs) {
      for (String arg : nativeImageArgs(Files.readString(config))) {
        if (!section.contains(arg)) {
          violations.add(root.relativize(config) + " passes '" + arg + "' to native-image, which '"
              + GRAALVM_SECTION + "' of docs/compatibility.md never mentions");
        }
      }
    }

    assertThat(violations)
        .as("docs/compatibility.md must name every flag the shipped metadata sets")
        .isEmpty();
  }

  /**
   * Every locale the metadata registers must be on the page too, written as code so the check
   * cannot be satisfied by the tag happening to occur inside an ordinary word.
   *
   * <p>The base locale is skipped: it is the empty string, which no page can be said to omit.
   */
  @Test
  void everyRegisteredLocaleIsNamedOnThePage() throws IOException {
    Path root = ReactorLayout.root();
    String section = graalvmSection(root);

    List<String> violations = new ArrayList<>();
    for (Path config : resourceConfigs(root)) {
      bundles(Files.readString(config)).forEach((bundle, locales) -> locales.stream()
          .filter(locale -> !locale.isEmpty())
          .filter(locale -> !section.contains("`" + locale + "`"))
          .forEach(locale -> violations.add(root.relativize(config) + " registers '" + bundle
              + "' for locale '" + locale + "', which '" + GRAALVM_SECTION
              + "' of docs/compatibility.md does not list")));
    }

    assertThat(violations)
        .as("docs/compatibility.md must list every locale the bundle registration covers")
        .isEmpty();
  }

  /**
   * The one check here that is not about the page: a translation added as a {@code _xx.properties}
   * file but never added to the {@code locales} array resolves to its key in a native image and to
   * the right text everywhere else — a divergence no JVM test can see, and the native smoke job
   * cannot see either, because it exercises the Quarkus path, which uses none of this metadata.
   *
   * <p>Deliberately one-directional. A registered locale with no file of its own is harmless: it
   * resolves to the base bundle, which is what {@code en} does here.
   */
  @Test
  void everyTranslationOnDiskIsRegisteredForNativeImage() throws IOException {
    Path root = ReactorLayout.root();

    List<String> violations = new ArrayList<>();
    for (Path config : resourceConfigs(root)) {
      Path module = ReactorLayout.owningModule(config);
      for (Map.Entry<String, Set<String>> bundle : bundles(Files.readString(config)).entrySet()) {
        Set<String> unregistered = new TreeSet<>(localesOnDisk(module, bundle.getKey()));
        unregistered.removeAll(bundle.getValue());
        if (!unregistered.isEmpty()) {
          violations.add(root.relativize(config) + ": bundle '" + bundle.getKey()
              + "' has files for locale(s) " + unregistered
              + " that its 'locales' array does not register — those translations would fall back"
              + " to their keys in a native image");
        }
      }
    }

    assertThat(violations)
        .as("every translation shipped must be registered for native image")
        .isEmpty();
  }

  /**
   * Every classpath resource a module reads or ships must be matched by one of its own inclusion
   * patterns. Like {@link #everyTranslationOnDiskIsRegisteredForNativeImage()}, this reads no
   * documentation: both sides are build inputs.
   *
   * <p>It is the only check here that survives a registration being <em>deleted</em>. Everything
   * else runs metadata → page, so removing an entry removes the thing being checked and passes by
   * having nothing left to disagree with — while the page goes on promising it. Dropping the {@code
   * ntfy.properties} include is the case that matters: {@code ConfigLoader} keeps compiling, the
   * whole suite stays green on the JVM, and the native binary silently stops finding its
   * configuration file.
   *
   * <p>Patterns are applied as the regexes GraalVM treats them as, not compared as text, so a
   * pattern that is rewritten but still covers the resource is correctly accepted.
   */
  @Test
  void everyResourceReadOrShippedIsRegisteredForNativeImage() throws IOException {
    Path root = ReactorLayout.root();

    List<String> violations = new ArrayList<>();
    for (Path config : resourceConfigs(root)) {
      Path module = ReactorLayout.owningModule(config);
      List<Pattern> registered = resourceIncludes(Files.readString(config)).stream()
          .map(p -> Pattern.compile(p.replace("\\\\", "\\")))
          .toList();

      Set<String> required = new TreeSet<>();
      required.addAll(classpathResourcesRead(module));
      required.addAll(shippedResourceNames(module));

      for (String resource : required) {
        if (registered.stream().noneMatch(p -> p.matcher(resource).matches())) {
          violations.add(module.getFileName() + " reads or ships the classpath resource '"
              + resource + "', which no include pattern in " + root.relativize(config)
              + " matches — it would be absent from a native image");
        }
      }
    }

    assertThat(violations)
        .as("every classpath resource a module reads or ships must be registered for native image")
        .isEmpty();
  }

  /**
   * The page presents itself as an inventory — "in full, and this is the whole of it" — and an
   * inventory is falsified by an addition, not only by a change to what is already listed. So every
   * metadata file the reactor ships must appear on the page against its own module.
   *
   * <p>This is the check the others cannot be: they each read one known filename, so they can only
   * ever confirm the files someone already thought to name. Drop a new {@code reflect-config.json}
   * beside an existing one and every other check here stays green while the page's central claim
   * quietly becomes false.
   */
  @Test
  void everyShippedMetadataFileIsNamedOnThePage() throws IOException {
    Path root = ReactorLayout.root();
    List<String> rows = graalvmSection(root).lines().toList();

    List<Path> shipped = ReactorLayout.nativeImageMetadata(root);
    assertThat(shipped)
        .as("no native-image metadata found in any reactor module — this gate reads what the jars"
            + " ship, so finding nothing means it is checking nothing")
        .isNotEmpty();

    List<String> violations = new ArrayList<>();
    for (Path file : shipped) {
      String module = ReactorLayout.owningModule(file).getFileName().toString();
      String name = file.getFileName().toString();
      boolean listed = rows.stream().anyMatch(row -> row.contains(module) && row.contains(name));
      if (!listed) {
        violations.add(root.relativize(file) + " is shipped, but '" + GRAALVM_SECTION
            + "' of docs/compatibility.md has no line naming both '" + module + "' and '" + name
            + "' — the page claims to inventory this metadata in full");
      }
    }

    assertThat(violations)
        .as("docs/compatibility.md must account for every native-image metadata file shipped")
        .isEmpty();
  }

  /**
   * Every resource registered for inclusion must be named on the page. Losing the {@code
   * ntfy.properties} registration is the difference between {@code ConfigLoader} finding its file
   * at image run time and silently not, so the page saying which resources are registered is the
   * only warning a reader gets.
   */
  @Test
  void everyRegisteredResourceIsNamedOnThePage() throws IOException {
    Path root = ReactorLayout.root();
    String section = graalvmSection(root);

    List<String> violations = new ArrayList<>();
    List<String> registered = new ArrayList<>();
    for (Path config : resourceConfigs(root)) {
      for (String pattern : resourceIncludes(Files.readString(config))) {
        registered.add(pattern);
        String head = literalHead(pattern);
        if (head.isEmpty() || !section.contains(head)) {
          violations.add(root.relativize(config) + " registers the resource pattern '" + pattern
              + "', whose literal part '" + head + "' does not appear in '" + GRAALVM_SECTION
              + "' of docs/compatibility.md");
        }
      }
    }

    // Emptying the includes would otherwise satisfy this check by having nothing left to check,
    // which is the failure it exists to prevent wearing the opposite mask: the page documents
    // registered resources, so finding none registered means the page and the metadata disagree.
    assertThat(registered)
        .as("no resource inclusions found anywhere in the reactor, yet '" + GRAALVM_SECTION
            + "' of docs/compatibility.md documents them — one of the two is wrong")
        .isNotEmpty();

    assertThat(violations)
        .as("docs/compatibility.md must name every resource the jars register for inclusion")
        .isEmpty();
  }

  /**
   * Every type registered for reflection must be named on the page.
   *
   * <p>Either spelling counts. The page names {@code NtfyJulAutoHandler} by its simple name, being
   * this project's own class, and {@code org.jboss.logmanager.ExtLogRecord} in full, being
   * somebody else's; both are how a reader would expect to find them, and neither is worth failing
   * a build over.
   */
  @Test
  void everyReflectivelyRegisteredTypeIsNamedOnThePage() throws IOException {
    Path root = ReactorLayout.root();
    String section = graalvmSection(root);

    List<String> violations = new ArrayList<>();
    for (Path config : ReactorLayout.nativeImageMetadata(root, "reflect-config.json")) {
      for (String type : reflectivelyRegisteredTypes(Files.readString(config))) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        if (!section.contains(type) && !section.contains(simple)) {
          violations.add(root.relativize(config) + " registers '" + type + "' for reflection, which"
              + " '" + GRAALVM_SECTION + "' of docs/compatibility.md never mentions");
        }
      }
    }

    assertThat(violations)
        .as("docs/compatibility.md must name every type the jars register for reflection")
        .isEmpty();
  }

  /**
   * The shipped {@code resource-config.json} files, asserted non-empty.
   *
   * <p>Most of the checks here iterate this list, so an empty one turns much of the gate into a
   * green no-op — which is exactly what a rename would produce, GraalVM having since folded these
   * files into {@code reachability-metadata.json}. A gate that stops checking must say so.
   */
  private static List<Path> resourceConfigs(Path root) throws IOException {
    List<Path> configs = ReactorLayout.nativeImageMetadata(root, "resource-config.json");
    assertThat(configs)
        .as("no resource-config.json found in any reactor module — if the metadata was migrated to"
            + " reachability-metadata.json, point this gate at the new name rather than leaving it"
            + " passing over an empty list")
        .isNotEmpty();
    return configs;
  }

  /**
   * The {@code bundles} array parsed as {@code bundle name -> registered locale tags}.
   *
   * <p>Each {@code { … }} entry is read as a whole and its fields looked up by name, rather than
   * matched as one fixed key order. GraalVM allows a {@code "condition"} alongside {@code "name"},
   * and JSON gives no meaning to field order, so a pattern anchored on {@code name} immediately
   * followed by {@code locales} would skip a perfectly valid entry — and skipping is silent here,
   * where the whole job is to notice things.
   */
  private static Map<String, Set<String>> bundles(String json) {
    Map<String, Set<String>> byName = new LinkedHashMap<>();
    for (String entry : objectsIn(arrayNamed("bundles", json))) {
      String name = topLevelName(entry);
      if (name == null) {
        continue;
      }
      Set<String> locales = new LinkedHashSet<>();
      Matcher array = LOCALES_FIELD.matcher(entry);
      if (array.find()) {
        Matcher locale = QUOTED.matcher(array.group(1));
        while (locale.find()) {
          locales.add(locale.group(1));
        }
      }
      byName.put(name, locales);
    }
    return byName;
  }

  /**
   * The contents of the named top-level array — everything between its brackets, both excluded.
   * Bounded rather than read to end-of-file, so a sibling array cannot leak into the result.
   */
  private static String arrayNamed(String field, String json) {
    int key = json.indexOf('"' + field + '"');
    if (key < 0) {
      return "";
    }
    int open = json.indexOf('[', key);
    if (open < 0) {
      return "";
    }
    int depth = 0;
    StringScan scan = new StringScan();
    for (int i = open; i < json.length(); i++) {
      char c = json.charAt(i);
      if (scan.isStructural(c)) {
        if (c == '[') {
          depth++;
        } else if (c == ']' && --depth == 0) {
          return json.substring(open + 1, i);
        }
      }
    }
    return "";
  }

  /** The top-level {@code { … }} objects of an array body, each returned whole. */
  private static List<String> objectsIn(String arrayBody) {
    List<String> objects = new ArrayList<>();
    int depth = 0;
    int start = -1;
    StringScan scan = new StringScan();
    for (int i = 0; i < arrayBody.length(); i++) {
      char c = arrayBody.charAt(i);
      if (!scan.isStructural(c)) {
        continue;
      }
      if (c == '{') {
        if (depth++ == 0) {
          start = i;
        }
      } else if (c == '}' && --depth == 0 && start >= 0) {
        objects.add(arrayBody.substring(start, i + 1));
      }
    }
    return objects;
  }

  /**
   * Tracks whether a left-to-right scan is currently inside a JSON string literal.
   *
   * <p>Counting brackets and braces without this is wrong on valid metadata, and wrong in the
   * direction that hides things: a {@code ]} or {@code &#125;} inside a string value — ordinary in a
   * resource pattern, which is a regex — closes an array early, so every entry after it is dropped
   * and the checks that read them pass by having nothing left to look at.
   */
  private static final class StringScan {

    private boolean inString;
    private boolean escaped;

    /** Feeds the next character and reports whether it is JSON structure rather than string text. */
    boolean isStructural(char c) {
      if (escaped) {
        escaped = false;
        return false;
      }
      if (c == '\\') {
        escaped = true;
        return false;
      }
      if (c == '"') {
        inString = !inString;
        return false;
      }
      return !inString;
    }
  }

  /**
   * The locale tags a bundle actually has files for, read from the module's resources: {@code ""}
   * for the base {@code Foo.properties}, and the suffix of each {@code Foo_xx.properties} beside it.
   */
  private static Set<String> localesOnDisk(Path module, String bundle) throws IOException {
    Path dir = module.resolve("src/main/resources").resolve(bundle.replace('.', '/')).getParent();
    String base = bundle.substring(bundle.lastIndexOf('.') + 1);
    Set<String> found = new TreeSet<>();
    if (dir == null || !Files.isDirectory(dir)) {
      return found;
    }
    try (var files = Files.list(dir)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.startsWith(base) || !name.endsWith(".properties")) {
          continue;
        }
        String suffix = name.substring(base.length(), name.length() - ".properties".length());
        if (suffix.isEmpty()) {
          found.add("");
        } else if (suffix.startsWith("_")) {
          found.add(suffix.substring(1));
        }
      }
    }
    return found;
  }

  /**
   * The whitespace-separated flags of a {@code native-image.properties} {@code Args =} entry.
   *
   * <p>Trailing-backslash continuations are joined first: {@code .properties} allows an entry to run
   * across lines, and reading them separately would both invent a flag ending in a backslash — a
   * false failure, the worst kind for a gate everyone's build runs — and silently drop the rest.
   */
  private static List<String> nativeImageArgs(String properties) {
    List<String> args = new ArrayList<>();
    for (String entry : logicalLines(properties)) {
      if (entry.startsWith("Args")) {
        int equals = entry.indexOf('=');
        if (equals >= 0 && entry.substring(0, equals).strip().equals("Args")) {
          String value = entry.substring(equals + 1).strip();
          if (!value.isEmpty()) {
            args.addAll(List.of(value.split("\\s+")));
          }
        }
      }
    }
    return args;
  }

  /** Physical lines folded into logical ones on a trailing backslash, comments dropped. */
  private static List<String> logicalLines(String properties) {
    List<String> entries = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : properties.lines().toList()) {
      String stripped = line.strip();
      if (current.isEmpty() && (stripped.startsWith("#") || stripped.startsWith("!"))) {
        continue;
      }
      if (stripped.endsWith("\\")) {
        current.append(stripped, 0, stripped.length() - 1).append(' ');
        continue;
      }
      entries.add(current.append(stripped).toString().strip());
      current.setLength(0);
    }
    if (!current.isEmpty()) {
      entries.add(current.toString().strip());
    }
    return entries;
  }

  /**
   * The literal head of a resource-inclusion pattern: everything up to the first regex construct,
   * with {@code \Q…\E} quoting honoured and backslash escapes unwrapped.
   *
   * <p>The patterns are regexes and the page is written for people, so the two can never be compared
   * whole: the metadata says {@code AlertMessages(_[a-zA-Z0-9_]+)?\.properties} where the page says
   * {@code AlertMessages(_…)?.properties}. The literal head is the part that names <em>which</em>
   * resource is registered, which is exactly what the page must not omit, and the part a regex
   * rewrite leaves alone.
   */
  private static String literalHead(String pattern) {
    String unescaped = pattern.replace("\\\\", "\\");
    StringBuilder head = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < unescaped.length(); i++) {
      char c = unescaped.charAt(i);
      char next = i + 1 < unescaped.length() ? unescaped.charAt(i + 1) : '\0';
      if (quoted) {
        if (c == '\\' && next == 'E') {
          quoted = false;
          i++;
        } else {
          head.append(c);
        }
      } else if (c == '\\' && next == 'Q') {
        quoted = true;
        i++;
      } else if (c == '\\' && next != '\0') {
        head.append(next);
        i++;
      } else if (REGEX_META.indexOf(c) >= 0) {
        break;
      } else {
        head.append(c);
      }
    }
    return head.toString();
  }

  /**
   * The classpath resource names a module loads at run time, read from its {@code getResource…}
   * call sites. An identifier argument is resolved against a {@code static final String} constant
   * in the same file, which is how this project writes them.
   */
  private static Set<String> classpathResourcesRead(Path module) throws IOException {
    Set<String> names = new TreeSet<>();
    Path sources = module.resolve("src/main/java");
    if (!Files.isDirectory(sources)) {
      return names;
    }
    try (var walk = Files.walk(sources)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        Matcher call = RESOURCE_CALL.matcher(source);
        while (call.find()) {
          String argument = call.group(1);
          if (argument.startsWith("\"")) {
            names.add(argument.substring(1, argument.length() - 1));
            continue;
          }
          Matcher constant =
              Pattern.compile("\\b" + Pattern.quote(argument) + "\\s*=\\s*\"([^\"]+)\"")
                  .matcher(source);
          if (constant.find()) {
            names.add(constant.group(1));
          }
        }
      }
    }
    names.removeIf(String::isEmpty);
    return names;
  }

  /**
   * The classpath names of the resource files a module ships, {@code META-INF} excluded: that tree
   * is build metadata consumed by tooling, not something the code loads by name at run time.
   */
  private static Set<String> shippedResourceNames(Path module) throws IOException {
    Set<String> names = new TreeSet<>();
    Path resources = module.resolve("src/main/resources");
    if (!Files.isDirectory(resources)) {
      return names;
    }
    try (var walk = Files.walk(resources)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        String name = resources.relativize(file).toString().replace('\\', '/');
        if (!name.startsWith("META-INF/")) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /** The {@code resources.includes} patterns of a {@code resource-config.json}. */
  private static List<String> resourceIncludes(String json) {
    List<String> patterns = new ArrayList<>();
    for (String entry : objectsIn(arrayNamed("includes", json))) {
      Matcher field = STRING_FIELD.matcher(entry);
      while (field.find()) {
        if (field.group(1).equals("pattern")) {
          patterns.add(field.group(2));
        }
      }
    }
    return patterns;
  }

  /** The {@code name} of every entry in a {@code reflect-config.json}'s top-level array. */
  private static List<String> reflectivelyRegisteredTypes(String json) {
    List<String> names = new ArrayList<>();
    int firstBracket = json.indexOf('[');
    if (firstBracket < 0) {
      return names;
    }
    for (String entry : objectsIn(json.substring(firstBracket + 1))) {
      String name = topLevelName(entry);
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * The entry's own {@code "name"}, ignoring any nested structure.
   *
   * <p>A flat scan cannot be used here: a reflection entry's {@code methods} array holds objects
   * that each have a {@code name} of their own, so it would report the constructor {@code <init>}
   * as a type to document. Reading only the outer object's fields also makes this independent of
   * where {@code name} sits among its siblings, which JSON does not fix.
   */
  private static String topLevelName(String object) {
    StringBuilder outer = new StringBuilder();
    int depth = 0;
    StringScan scan = new StringScan();
    for (int i = 0; i < object.length(); i++) {
      char c = object.charAt(i);
      if (scan.isStructural(c)) {
        if (c == '{' || c == '[') {
          depth++;
          if (depth == 1) {
            continue;
          }
        } else if (c == '}' || c == ']') {
          depth--;
          if (depth == 0) {
            continue;
          }
        }
      }
      if (depth == 1) {
        outer.append(c);
      }
    }
    Matcher field = STRING_FIELD.matcher(outer);
    while (field.find()) {
      if (field.group(1).equals("name")) {
        return field.group(2);
      }
    }
    return null;
  }

  /** The GraalVM section's body: its heading down to the next {@code ## } heading. */
  private static String graalvmSection(Path root) throws IOException {
    List<String> body = new ArrayList<>();
    boolean inside = false;
    for (String line : Files.readAllLines(root.resolve("docs/compatibility.md"))) {
      if (line.startsWith("## ")) {
        inside = line.startsWith(GRAALVM_SECTION);
        continue;
      }
      if (inside) {
        body.add(line);
      }
    }
    assertThat(body)
        .as("docs/compatibility.md must still have a '" + GRAALVM_SECTION + "' section")
        .isNotEmpty();
    return String.join("\n", body);
  }
}
