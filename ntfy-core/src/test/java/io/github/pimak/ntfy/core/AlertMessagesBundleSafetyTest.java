package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Locks the credential-safety and translation contracts for every shipped {@code AlertMessages}
 * bundle. These invariants are what let a translator drop in a new {@code AlertMessages_xx.properties}
 * without ever being able to (a) interpolate a credential into a message, (b) change the argument
 * shape a composer expects, or (c) ship an unparseable {@link MessageFormat} pattern.
 *
 * <p>Whenever a new language is added, extend {@link #TRANSLATION_RESOURCES} so its bundle is checked
 * here too.
 */
class AlertMessagesBundleSafetyTest {

  private static final String BASE_RESOURCE =
      "io/github/pimak/ntfy/core/AlertMessages.properties";

  /** Every shipped translation bundle (base excluded). Add a row when a language is added. */
  private static final Map<String, String> TRANSLATION_RESOURCES =
      Map.of("fr", "io/github/pimak/ntfy/core/AlertMessages_fr.properties");

  /**
   * MessageFormat pattern keys mapped to the exact argument count the Java composers pass. A
   * translation may move {@code {0}}/{@code {1}} around in the text but must keep exactly this
   * placeholder set — adding a placeholder is what could interpolate an unintended value, and
   * removing one would silently swallow information, so both fail the exact-count assertion below.
   */
  private static final Map<String, Integer> PATTERN_KEY_ARG_COUNTS = buildPatternArgCounts();

  private static Map<String, Integer> buildPatternArgCounts() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("status.active", 2);
    m.put("publish.failed.withStatus", 2);
    m.put("publish.failed.noStatus", 1);
    m.put("status.exclusions.list", 1);
    m.put("digest.title", 2);
    m.put("digest.body.summary", 2);
    m.put("digest.body.loggerLine", 2);
    m.put("digest.body.overflow", 1);
    m.put("window.minute", 1);
    m.put("window.minutes", 1);
    m.put("window.second", 1);
    m.put("window.seconds", 1);
    return m;
  }

  @Test
  void baseBundleLoads() throws IOException {
    assertThat(load(BASE_RESOURCE)).isNotEmpty();
  }

  @Test
  void noPlaceholderKeysContainNoBrace_inEveryShippedBundle() throws IOException {
    Properties base = load(BASE_RESOURCE);
    for (String resource : allResources()) {
      Properties props = load(resource);
      for (String key : base.stringPropertyNames()) {
        if (PATTERN_KEY_ARG_COUNTS.containsKey(key)) {
          continue; // pattern keys are allowed (and required) to carry {n} placeholders
        }
        String value = props.getProperty(key);
        if (value == null) {
          continue; // key absent from this translation -> resolves to English at runtime
        }
        // A '{' in a non-pattern key would mean a translation invented a placeholder slot — the one
        // structural way a credential could ever reach a fixed status/label string.
        assertThat(value)
            .as("key '%s' in %s must contain no MessageFormat placeholder", key, resource)
            .doesNotContain("{");
      }
    }
  }

  @Test
  void patternKeysHaveTheExactArgumentCount_inEveryShippedBundle() throws IOException {
    for (String resource : allResources()) {
      Properties props = load(resource);
      for (Map.Entry<String, Integer> e : PATTERN_KEY_ARG_COUNTS.entrySet()) {
        String pattern = props.getProperty(e.getKey());
        if (pattern == null) {
          continue; // absent from this translation -> falls back to the English base
        }
        int expected = e.getValue();
        MessageFormat format = new MessageFormat(pattern);
        // Highest referenced index + 1 catches an ADDED placeholder ({2} where only {0}/{1} exist).
        assertThat(format.getFormatsByArgumentIndex().length)
            .as("pattern key '%s' in %s must reference no argument beyond index %d",
                e.getKey(), resource, expected - 1)
            .isEqualTo(expected);
        // The length alone would still pass if a LOWER index were dropped ({1} kept, {0} gone) —
        // format with recognizable sentinels and require every one to actually appear.
        Object[] sentinels = new Object[expected];
        for (int i = 0; i < expected; i++) {
          sentinels[i] = "«ARG" + i + "»";
        }
        String formatted = format.format(sentinels);
        for (int i = 0; i < expected; i++) {
          assertThat(formatted)
              .as("pattern key '%s' in %s must actually use argument {%d}",
                  e.getKey(), resource, i)
              .contains((String) sentinels[i]);
        }
      }
    }
  }

  @Test
  void everyPatternParses_inEveryShippedBundle() throws IOException {
    for (String resource : allResources()) {
      Properties props = load(resource);
      for (String key : PATTERN_KEY_ARG_COUNTS.keySet()) {
        String pattern = props.getProperty(key);
        if (pattern == null) {
          continue;
        }
        assertThatCode(() -> new MessageFormat(pattern))
            .as("pattern key '%s' in %s must parse (check for an unescaped ' or { )", key, resource)
            .doesNotThrowAnyException();
      }
    }
  }

  @Test
  void everyTranslationKeyIsASubsetOfTheBase() throws IOException {
    Properties base = load(BASE_RESOURCE);
    for (String resource : TRANSLATION_RESOURCES.values()) {
      Properties props = load(resource);
      for (String key : props.stringPropertyNames()) {
        // A typo'd key in a translation would never be read (the accessor asks for the correct key
        // and silently falls back to English) — catch it here as a dead entry instead.
        assertThat(base.stringPropertyNames())
            .as("translation %s defines key '%s' that does not exist in the base bundle",
                resource, key)
            .contains(key);
      }
    }
  }

  @Test
  void translatedInstanceDiffersFromEnglish() {
    AlertMessages en = AlertMessages.forLocale(Locale.ENGLISH);
    AlertMessages fr = AlertMessages.forLocale(Locale.forLanguageTag("fr"));

    assertThat(fr.statusInvalidUrl()).isNotEqualTo(en.statusInvalidUrl());
    assertThat(fr.digestTitle("App", 3)).isNotEqualTo(en.digestTitle("App", 3));
  }

  @Test
  void unshippedLocaleFallsBackWholesaleToEnglish() {
    AlertMessages en = AlertMessages.forLocale(Locale.ENGLISH);
    // Japanese ships no bundle: getNoFallbackControl routes ja -> base (English) with no
    // host-JVM-default-locale intermediate, so every accessor returns the exact English text.
    AlertMessages ja = AlertMessages.forLocale(Locale.forLanguageTag("ja"));

    assertThat(ja.statusInvalidUrl()).isEqualTo(en.statusInvalidUrl());
    assertThat(ja.statusIncompleteBasicAuth()).isEqualTo(en.statusIncompleteBasicAuth());
    assertThat(ja.digestTitle("App", 3)).isEqualTo(en.digestTitle("App", 3));
    assertThat(ja.describeWindow(java.time.Duration.ofMinutes(3), java.time.Duration.ofMinutes(3)))
        .isEqualTo(en.describeWindow(java.time.Duration.ofMinutes(3), java.time.Duration.ofMinutes(3)));
  }

  @Test
  void everyBaseKeyResolvesInFrenchLocale_provingPerKeyFallbackChainIsIntact() throws IOException {
    Properties base = load(BASE_RESOURCE);
    java.util.ResourceBundle fr =
        java.util.ResourceBundle.getBundle(
            "io.github.pimak.ntfy.core.AlertMessages",
            Locale.forLanguageTag("fr"),
            AlertMessages.class.getClassLoader(),
            java.util.ResourceBundle.Control.getNoFallbackControl(
                java.util.ResourceBundle.Control.FORMAT_PROPERTIES));
    // Every key defined in the base must be resolvable through the French bundle's parent chain
    // (translated when present, English-fallback otherwise) — never a MissingResourceException.
    for (String key : base.stringPropertyNames()) {
      assertThatCode(() -> fr.getString(key))
          .as("base key '%s' must resolve for the fr bundle via the parent chain", key)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void credentialSafety_translatedStatusActiveStillStripsUserinfo() {
    // The userinfo-stripping regex runs in Java before MessageFormat, so it is locale-independent:
    // even the French ACTIVE line can never echo a password.
    AlertMessages fr = AlertMessages.forLocale(Locale.forLanguageTag("fr"));
    String line = fr.statusActive("https://user:p@ss@ntfy.example.com/x", "t");
    assertThat(line).contains("https://ntfy.example.com/x").doesNotContain("p@ss").doesNotContain("user:");
  }

  private static List<String> allResources() {
    java.util.List<String> all = new java.util.ArrayList<>();
    all.add(BASE_RESOURCE);
    all.addAll(TRANSLATION_RESOURCES.values());
    return all;
  }

  private static Properties load(String resource) throws IOException {
    Properties props = new Properties();
    try (InputStream in =
        AlertMessagesBundleSafetyTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("resource %s must be on the classpath", resource).isNotNull();
      props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return props;
  }
}
