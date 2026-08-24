package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ConfigLoader#load(Function, Properties, Properties)} through its package-private
 * seam: the sysprop &gt; env &gt; file &gt; default precedence, blank-value masking, value trimming,
 * kebab-to-key mapping, the {@code endpointFromClasspathFile} trust flag, silent fallback for
 * malformed int/duration values, and boolean parsing. No classpath fixture is needed because the
 * seam accepts {@link Properties} directly; nothing here touches the real JVM environment.
 */
class ConfigLoaderTest {

  /** Builds an env lookup backed by a plain map (returns {@code null} for absent keys). */
  private static Function<String, String> env(Map<String, String> map) {
    return map::get;
  }

  private static Properties props(String... keyValues) {
    Properties p = new Properties();
    for (int i = 0; i < keyValues.length; i += 2) {
      p.setProperty(keyValues[i], keyValues[i + 1]);
    }
    return p;
  }

  // --- Precedence -------------------------------------------------------------------------------

  @Test
  void sysprop_winsOverEnvAndFile() {
    Function<String, String> env = env(Map.of("NTFY_URL", "https://env.example"));
    Properties file = props("ntfy.url", "https://file.example");
    Properties sys = props("ntfy.url", "https://sys.example");

    NtfyConfig config = ConfigLoader.load(env, file, sys);

    assertThat(config.getUrl()).isEqualTo("https://sys.example");
  }

  @Test
  void env_winsOverFile_whenNoSysprop() {
    Function<String, String> env = env(Map.of("NTFY_URL", "https://env.example"));
    Properties file = props("ntfy.url", "https://file.example");

    NtfyConfig config = ConfigLoader.load(env, file, null);

    assertThat(config.getUrl()).isEqualTo("https://env.example");
  }

  @Test
  void file_winsOverDefault_whenNothingHigher() {
    Properties file = props("ntfy.url", "https://file.example");

    NtfyConfig config = ConfigLoader.load(null, file, null);

    assertThat(config.getUrl()).isEqualTo("https://file.example");
  }

  @Test
  void noLayerSuppliesValue_defaultStands() {
    NtfyConfig config = ConfigLoader.load(null, null, null);

    assertThat(config.getUrl()).isNull();
  }

  // --- Env-var key mapping ----------------------------------------------------------------------

  @Test
  void kebabKey_mapsToUpperSnakeEnvVar() {
    Function<String, String> env = env(Map.of("NTFY_APP_NAME", "billing-svc"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getAppName()).isEqualTo("billing-svc");
  }

  @Test
  void kebabKey_mapsToDotSysprop() {
    Properties sys = props("ntfy.app-name", "billing-svc");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.getAppName()).isEqualTo("billing-svc");
  }

  @Test
  void multiWordKebabKey_mapsToUpperSnakeEnvVar() {
    Function<String, String> env = env(Map.of("NTFY_MAX_ALERTS_PER_WINDOW", "9"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getMaxAlertsPerWindow()).isEqualTo(9);
  }

  // --- Blank-value masking & trimming -----------------------------------------------------------

  @Test
  void blankHigherLayer_doesNotShadowLowerLayer() {
    Properties file = props("ntfy.topic", "real");
    Properties sys = props("ntfy.topic", "   ");

    NtfyConfig config = ConfigLoader.load(null, file, sys);

    assertThat(config.getTopic()).isEqualTo("real");
  }

  @Test
  void blankAtEveryLayer_defaultStands() {
    Function<String, String> env = env(Map.of("NTFY_TOPIC", "   "));
    Properties file = props("ntfy.topic", "  ");
    Properties sys = props("ntfy.topic", " ");

    NtfyConfig config = ConfigLoader.load(env, file, sys);

    assertThat(config.getTopic()).isNull();
  }

  @Test
  void resolvedValue_isTrimmed() {
    Function<String, String> env = env(Map.of("NTFY_TOPIC", " alerts "));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getTopic()).isEqualTo("alerts");
  }

  // --- endpointFromClasspathFile trust flag -----------------------------------------------------

  @Test
  void urlFromFileOnly_flagsEndpointFromClasspathFile() {
    Properties file = props("ntfy.url", "https://file.example");

    NtfyConfig config = ConfigLoader.load(null, file, null);

    assertThat(config.isEndpointFromClasspathFile()).isTrue();
  }

  @Test
  void urlFromEnv_doesNotFlag() {
    Function<String, String> env = env(Map.of("NTFY_URL", "https://env.example"));
    Properties file = props("ntfy.url", "https://file.example");

    NtfyConfig config = ConfigLoader.load(env, file, null);

    assertThat(config.isEndpointFromClasspathFile()).isFalse();
  }

  @Test
  void urlFromSysprop_doesNotFlag() {
    Properties sys = props("ntfy.url", "https://sys.example");
    Properties file = props("ntfy.url", "https://file.example");

    NtfyConfig config = ConfigLoader.load(null, file, sys);

    assertThat(config.isEndpointFromClasspathFile()).isFalse();
  }

  @Test
  void noUrlAnywhere_doesNotFlag() {
    NtfyConfig config = ConfigLoader.load(null, null, null);

    assertThat(config.isEndpointFromClasspathFile()).isFalse();
  }

  @Test
  void blankFileUrlWithRealEnvUrl_doesNotFlag() {
    // The blank file layer is not the source of the URL, so the flag must stay false even though a
    // file value is technically present.
    Function<String, String> env = env(Map.of("NTFY_URL", "https://env.example"));
    Properties file = props("ntfy.url", "   ");

    NtfyConfig config = ConfigLoader.load(env, file, null);

    assertThat(config.getUrl()).isEqualTo("https://env.example");
    assertThat(config.isEndpointFromClasspathFile()).isFalse();
  }

  // --- allow-classpath-endpoint opt-in ----------------------------------------------------------

  @Test
  void allowClasspathEndpoint_defaultsToFalse() {
    NtfyConfig config = ConfigLoader.load(null, null, null);

    assertThat(config.isAllowClasspathEndpoint()).isFalse();
  }

  @Test
  void allowClasspathEndpoint_fromSysprop() {
    Properties sys = props("ntfy.allow-classpath-endpoint", "true");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.isAllowClasspathEndpoint()).isTrue();
  }

  @Test
  void allowClasspathEndpoint_fromEnv() {
    Function<String, String> env = env(Map.of("NTFY_ALLOW_CLASSPATH_ENDPOINT", "true"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.isAllowClasspathEndpoint()).isTrue();
  }

  @Test
  void allowClasspathEndpoint_sysprop_winsOverEnv() {
    Function<String, String> env = env(Map.of("NTFY_ALLOW_CLASSPATH_ENDPOINT", "true"));
    Properties sys = props("ntfy.allow-classpath-endpoint", "false");

    NtfyConfig config = ConfigLoader.load(env, null, sys);

    assertThat(config.isAllowClasspathEndpoint()).isFalse();
  }

  @Test
  void allowClasspathEndpoint_cannotBeGrantedByTheClasspathFileItself() {
    // The whole point of the flag is guarding against a malicious ntfy.properties on the classpath,
    // so that file must never be able to opt itself in.
    Properties file =
        props(
            "ntfy.url", "https://file.example",
            "ntfy.topic", "alerts",
            "ntfy.allow-classpath-endpoint", "true");

    NtfyConfig config = ConfigLoader.load(null, file, null);

    assertThat(config.isEndpointFromClasspathFile()).isTrue();
    assertThat(config.isAllowClasspathEndpoint()).isFalse();
  }

  // --- Silent fallback for malformed values -----------------------------------------------------

  @Test
  void malformedInt_keepsDefault_withoutThrowing() {
    int defaultFrames = NtfyConfig.builder().build().getMaxStackFrames();
    Function<String, String> env = env(Map.of("NTFY_MAX_STACK_FRAMES", "abc"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getMaxStackFrames()).isEqualTo(defaultFrames);
  }

  @Test
  void validInt_isApplied() {
    Function<String, String> env = env(Map.of("NTFY_MAX_STACK_FRAMES", "7"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getMaxStackFrames()).isEqualTo(7);
  }

  @Test
  void malformedDuration_keepsDefault_withoutThrowing() {
    Duration defaultTimeout = NtfyConfig.builder().build().getConnectTimeout();
    Function<String, String> env = env(Map.of("NTFY_CONNECT_TIMEOUT", "notaduration"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getConnectTimeout()).isEqualTo(defaultTimeout);
  }

  @Test
  void validDuration_isApplied_throughDurationParser() {
    Function<String, String> env = env(Map.of("NTFY_CONNECT_TIMEOUT", "5s"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
  }

  // --- enabled boolean parsing ------------------------------------------------------------------

  @Test
  void enabledTrue_isParsed() {
    Function<String, String> env = env(Map.of("NTFY_ENABLED", "true"));

    assertThat(ConfigLoader.load(env, null, null).isEnabled()).isTrue();
  }

  @Test
  void enabledFalse_isParsed() {
    Function<String, String> env = env(Map.of("NTFY_ENABLED", "false"));

    assertThat(ConfigLoader.load(env, null, null).isEnabled()).isFalse();
  }

  @Test
  void enabledTrue_withSurroundingWhitespace_isParsed() {
    Function<String, String> env = env(Map.of("NTFY_ENABLED", "TRUE "));

    assertThat(ConfigLoader.load(env, null, null).isEnabled()).isTrue();
  }

  @Test
  void enabledGarbage_parsesToFalse() {
    // Builder default is enabled=true, so a garbage value flipping the flag to false is a real
    // signal (Boolean.parseBoolean maps anything non-"true" to false).
    Function<String, String> env = env(Map.of("NTFY_ENABLED", "yes"));

    assertThat(ConfigLoader.load(env, null, null).isEnabled()).isFalse();
  }

  // --- Straight passthrough fields & robustness -------------------------------------------------

  @Test
  void excludedLoggersCsv_isSplitAndTrimmed() {
    Function<String, String> env =
        env(Map.of("NTFY_EXCLUDED_LOGGERS", " com.foo , com.bar "));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getExcludedLoggerPrefixes()).containsExactly("com.foo", "com.bar");
  }

  @Test
  void excludedExceptionTypesCsv_isSplitAndTrimmedAndDropsBlanks() {
    Function<String, String> env =
        env(
            Map.of(
                "NTFY_EXCLUDED_EXCEPTION_TYPES",
                " org.apache.catalina.connector.ClientAbortException , ,java.io.IOException "));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getExcludedExceptionTypes())
        .containsExactly("org.apache.catalina.connector.ClientAbortException", "java.io.IOException");
  }

  @Test
  void cacheAndFirebase_defaultToTrue() {
    // Only the defaults, which is all this class can see. That these defaults put no header on the
    // wire is asserted where it can be observed: AlertEngineDeliveryPrivacyIT.
    NtfyConfig config = ConfigLoader.load(env(Map.of()), null, null);

    assertThat(config.isCache()).isTrue();
    assertThat(config.isFirebase()).isTrue();
  }

  @Test
  void cacheYes_meansCachingOn_notOff() {
    // Boolean.parseBoolean would read "yes" as false and send Cache: no — the exact opposite of
    // what the operator asked for, and it costs every offline subscriber their alerts silently.
    NtfyConfig config = ConfigLoader.load(env(Map.of("NTFY_CACHE", "yes")), null, null);

    assertThat(config.isCache()).isTrue();
    assertThat(config.isDeliveryPolicyValueRejected()).isFalse();
  }

  @Test
  void commonFalsySpellingsAreUnderstood() {
    assertThat(ConfigLoader.load(env(Map.of("NTFY_CACHE", "no")), null, null).isCache()).isFalse();
    assertThat(ConfigLoader.load(env(Map.of("NTFY_FIREBASE", "0")), null, null).isFirebase())
        .isFalse();
  }

  @Test
  void anUnreadableFlagKeepsTheDefaultAndIsReported() {
    // Guessing here is what turns a typo into lost alerts; the engine warns about this at start().
    NtfyConfig config = ConfigLoader.load(env(Map.of("NTFY_CACHE", "maybe")), null, null);

    assertThat(config.isCache()).isTrue();
    assertThat(config.isDeliveryPolicyValueRejected()).isTrue();
  }

  @Test
  void cacheAndFirebase_areIndependentlySettable() {
    // The combination that must stay reachable: no Firebase forwarding, but server-side caching
    // kept so offline subscribers still get their alerts.
    Function<String, String> env =
        env(Map.of("NTFY_FIREBASE", "false", "NTFY_CACHE", "true"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.isFirebase()).isFalse();
    assertThat(config.isCache()).isTrue();
  }

  @Test
  void excludedExceptionTypes_defaultsToEmpty() {
    // The deny-list is opt-in: an unset key must never gate anything out.
    assertThat(ConfigLoader.load(env(Map.of()), null, null).getExcludedExceptionTypes()).isEmpty();
  }

  @Test
  void includeMdcKeysCsv_isSplitAndTrimmed() {
    Function<String, String> env =
        env(Map.of("NTFY_INCLUDE_MDC_KEYS", " correlationId , tenant ,, "));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    // Blanks are dropped, entries trimmed, and the CONFIGURED ORDER is preserved — it is also the
    // order the context lines are rendered in.
    assertThat(config.getIncludeMdcKeys()).containsExactly("correlationId", "tenant");
  }

  @Test
  void includeMdcKeys_sysPropBeatsEnvBeatsFile() {
    Function<String, String> env = env(Map.of("NTFY_INCLUDE_MDC_KEYS", "fromEnv"));
    // Unlike allow-classpath-endpoint, this key DOES read the classpath-file layer: it cannot
    // redirect where alerts go, only name which allow-listed context lines are rendered.
    Properties file = props("ntfy.include-mdc-keys", "fromFile");
    Properties sys = props("ntfy.include-mdc-keys", "fromSysProp");

    assertThat(ConfigLoader.load(env, file, sys).getIncludeMdcKeys())
        .containsExactly("fromSysProp");
    assertThat(ConfigLoader.load(env, file, null).getIncludeMdcKeys()).containsExactly("fromEnv");
    assertThat(ConfigLoader.load(null, file, null).getIncludeMdcKeys()).containsExactly("fromFile");
  }

  @Test
  void includeMdcKeys_blankValue_isTreatedAsAbsent() {
    Function<String, String> env = env(Map.of("NTFY_INCLUDE_MDC_KEYS", "   "));
    Properties file = props("ntfy.include-mdc-keys", "fromFile");

    // A blank layer never masks a lower one (resolve() treats blank as absent).
    assertThat(ConfigLoader.load(env, file, null).getIncludeMdcKeys()).containsExactly("fromFile");
  }

  @Test
  void includeMdcKeys_absent_defaultsToEmptyList() {
    // The whole feature is opt-in: with nothing configured, no MDC value can ever be published.
    assertThat(ConfigLoader.load(null, null, null).getIncludeMdcKeys()).isEmpty();
  }

  // --- Level routing (warn-topic / warn-priority / warn-tags) -----------------------------------

  @Test
  void warnRoutingKeys_absent_leaveAlertingErrorOnly() {
    NtfyConfig config = ConfigLoader.load(null, null, null);

    assertThat(config.getWarnTopic()).isNull();
    assertThat(config.isWarnRoutingEnabled()).isFalse();
    // The styling defaults exist regardless, but are only ever consulted once a topic is named.
    assertThat(config.getWarnPriority()).isEqualTo("default");
    assertThat(config.getWarnTags()).isEqualTo("warning");
  }

  @Test
  void warnTopic_aloneEnablesWarnRouting() {
    Function<String, String> env = env(Map.of("NTFY_WARN_TOPIC", "alerts-warn"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    // No separate boolean: naming a destination IS the opt-in.
    assertThat(config.isWarnRoutingEnabled()).isTrue();
    assertThat(config.getWarnTopic()).isEqualTo("alerts-warn");
  }

  @Test
  void warnRoutingKeys_sysPropBeatsEnvBeatsFile() {
    Function<String, String> env =
        env(Map.of("NTFY_WARN_TOPIC", "fromEnv", "NTFY_WARN_PRIORITY", "low"));
    Properties file = props("ntfy.warn-topic", "fromFile", "ntfy.warn-tags", "eyes");
    Properties sys = props("ntfy.warn-topic", "fromSysProp");

    assertThat(ConfigLoader.load(env, file, sys).getWarnTopic()).isEqualTo("fromSysProp");
    assertThat(ConfigLoader.load(env, file, null).getWarnTopic()).isEqualTo("fromEnv");
    assertThat(ConfigLoader.load(null, file, null).getWarnTopic()).isEqualTo("fromFile");
    assertThat(ConfigLoader.load(env, file, null).getWarnPriority()).isEqualTo("low");
    assertThat(ConfigLoader.load(env, file, null).getWarnTags()).isEqualTo("eyes");
  }

  @Test
  void warnTopic_blankValue_isTreatedAsAbsent() {
    Function<String, String> env = env(Map.of("NTFY_WARN_TOPIC", "   "));
    Properties file = props("ntfy.warn-topic", "fromFile");

    assertThat(ConfigLoader.load(env, file, null).getWarnTopic()).isEqualTo("fromFile");
  }

  @Test
  void warnTopicFromClasspathFileAlone_isFlagged() {
    // warn-topic names a DESTINATION, so its origin is tracked exactly like `url`'s — the engine
    // refuses a classpath-only value unless allow-classpath-endpoint is set.
    Properties file = props("ntfy.warn-topic", "fromFile");

    assertThat(ConfigLoader.load(null, file, null).isWarnTopicFromClasspathFile()).isTrue();
  }

  @Test
  void warnTopicFromEnvOrSysprop_isNotFlagged_evenWhenTheFileAlsoSetsIt() {
    Function<String, String> env = env(Map.of("NTFY_WARN_TOPIC", "fromEnv"));
    Properties file = props("ntfy.warn-topic", "fromFile");
    Properties sys = props("ntfy.warn-topic", "fromSysProp");

    // An operator-supplied value wins and carries no supply-chain doubt with it.
    assertThat(ConfigLoader.load(env, file, null).isWarnTopicFromClasspathFile()).isFalse();
    assertThat(ConfigLoader.load(null, file, sys).isWarnTopicFromClasspathFile()).isFalse();
  }

  @Test
  void warnTopicAbsentEverywhere_isNotFlagged() {
    assertThat(ConfigLoader.load(null, null, null).isWarnTopicFromClasspathFile()).isFalse();
  }

  @Test
  void errorPriority_isWiredThrough() {
    Function<String, String> env = env(Map.of("NTFY_ERROR_PRIORITY", "max"));

    assertThat(ConfigLoader.load(env, null, null).getErrorPriority()).isEqualTo("max");
  }

  // --- Locale -----------------------------------------------------------------------------------

  @Test
  void locale_fromEnv_isParsedToLocale() {
    Function<String, String> env = env(Map.of("NTFY_LOCALE", "fr"));

    assertThat(ConfigLoader.load(env, null, null).getLocale())
        .isEqualTo(java.util.Locale.FRENCH);
  }

  @Test
  void locale_fromSysprop_winsOverEnv() {
    Function<String, String> env = env(Map.of("NTFY_LOCALE", "de"));
    Properties sys = props("ntfy.locale", "fr");

    assertThat(ConfigLoader.load(env, null, sys).getLocale())
        .isEqualTo(java.util.Locale.FRENCH);
  }

  @Test
  void locale_absent_defaultsToEnglish() {
    assertThat(ConfigLoader.load(null, null, null).getLocale())
        .isEqualTo(java.util.Locale.ENGLISH);
  }

  @Test
  void locale_garbageTag_keepsEnglishDefault() {
    Function<String, String> env = env(Map.of("NTFY_LOCALE", "!!!"));

    assertThat(ConfigLoader.load(env, null, null).getLocale())
        .isEqualTo(java.util.Locale.ENGLISH);
  }

  @Test
  void locale_undeterminedLanguageTags_keepEnglishDefault() {
    // "und-Latn"/"und-US" carry a script/region but no language ("und" language subtag parses to an
    // empty Locale.getLanguage()), and "x-private" is private-use only — none selects a language,
    // so all must keep the English default rather than an undetermined locale.
    for (String tag : new String[] {"und", "und-Latn", "und-US", "x-private"}) {
      Function<String, String> env = env(Map.of("NTFY_LOCALE", tag));

      assertThat(ConfigLoader.load(env, null, null).getLocale())
          .as("tag: %s", tag)
          .isEqualTo(java.util.Locale.ENGLISH);
    }
  }

  @Test
  void requireHttpsForCredentials_defaultsToTrue() {
    assertThat(ConfigLoader.load(k -> null, null, null).isRequireHttpsForCredentials()).isTrue();
  }

  // Both wiring tests opt OUT ("false") — the non-default value since 2.0 — so a passing assertion
  // can only come from the key actually reaching the builder, never from the default standing.
  @Test
  void requireHttpsForCredentials_isWiredThroughFromEnv() {
    Function<String, String> env =
        env(Map.of("NTFY_REQUIRE_HTTPS_FOR_CREDENTIALS", "false"));

    assertThat(ConfigLoader.load(env, null, null).isRequireHttpsForCredentials()).isFalse();
  }

  @Test
  void requireHttpsForCredentials_isWiredThroughFromSystemProperty() {
    Properties sys = new Properties();
    sys.setProperty("ntfy.require-https-for-credentials", "false");

    assertThat(
            ConfigLoader.load(k -> null, null, sys).isRequireHttpsForCredentials())
        .isFalse();
  }

  @Test
  void allNullLayers_yieldAllDefaultsWithoutNpe() {
    NtfyConfig defaults = NtfyConfig.builder().build();

    NtfyConfig config = ConfigLoader.load(null, null, null);

    assertThat(config.getUrl()).isNull();
    assertThat(config.getTopic()).isNull();
    assertThat(config.getMaxStackFrames()).isEqualTo(defaults.getMaxStackFrames());
    assertThat(config.getConnectTimeout()).isEqualTo(defaults.getConnectTimeout());
    assertThat(config.getRequestTimeout()).isEqualTo(defaults.getRequestTimeout());
    assertThat(config.getSuppressionWindow()).isEqualTo(defaults.getSuppressionWindow());
    assertThat(config.getMaxAlertsPerWindow()).isEqualTo(defaults.getMaxAlertsPerWindow());
    assertThat(config.getDigestPriority()).isEqualTo(defaults.getDigestPriority());
    assertThat(config.isEnabled()).isEqualTo(defaults.isEnabled());
    assertThat(config.isEndpointFromClasspathFile()).isFalse();
  }

  // --- startup-ping self-test -------------------------------------------------------------------

  @Test
  void startupPing_defaultsToOff() {
    NtfyConfig config = ConfigLoader.load(null, null, null);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.OFF);
    assertThat(config.isStartupPingFailFast()).isFalse();
    assertThat(config.isStartupPingValueRejected()).isFalse();
  }

  @Test
  void startupPing_fromSysprop() {
    Properties sys = props("ntfy.startup-ping", "publish");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PUBLISH);
  }

  @Test
  void startupPing_fromEnv() {
    Function<String, String> env = env(Map.of("NTFY_STARTUP_PING", "probe"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PROBE);
  }

  @Test
  void startupPing_fromClasspathFile() {
    // Unlike allow-classpath-endpoint, this key IS readable from the file layer: it only probes the
    // endpoint the config already points at, so it can neither redirect alerts nor grant trust.
    Properties file = props("ntfy.startup-ping", "probe");

    NtfyConfig config = ConfigLoader.load(null, file, null);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PROBE);
  }

  @Test
  void startupPing_sysprop_winsOverEnv() {
    Function<String, String> env = env(Map.of("NTFY_STARTUP_PING", "publish"));
    Properties sys = props("ntfy.startup-ping", "probe");

    NtfyConfig config = ConfigLoader.load(env, null, sys);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PROBE);
  }

  @Test
  void startupPing_unparseableValueKeepsTheDefaultButIsFlagged() {
    Properties sys = props("ntfy.startup-ping", "prob");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.OFF);
    assertThat(config.isStartupPingValueRejected()).isTrue();
  }

  @Test
  void startupPingFailFast_fromSysprop() {
    Properties sys = props("ntfy.startup-ping-fail-fast", "true");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.isStartupPingFailFast()).isTrue();
  }

  @Test
  void startupPingWarn_isIndependentOfStartupPing() {
    Properties sys = props("ntfy.startup-ping", "probe");
    sys.setProperty("ntfy.startup-ping-warn", "publish");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PROBE);
    assertThat(config.getStartupPingWarn()).isEqualTo(StartupPingMode.PUBLISH);
  }

  @Test
  void startupPingWarn_defaultsToOffEvenWhenTheErrorRouteIsOn() {
    Properties sys = props("ntfy.startup-ping", "publish");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    // Enabling one route must never opt the other in: in publish mode each route costs its own
    // notification on every boot.
    assertThat(config.getStartupPingWarn()).isEqualTo(StartupPingMode.OFF);
  }

  @Test
  void startupPingWarn_fromEnv() {
    Function<String, String> env = env(Map.of("NTFY_STARTUP_PING_WARN", "probe"));

    assertThat(ConfigLoader.load(env, null, null).getStartupPingWarn())
        .isEqualTo(StartupPingMode.PROBE);
  }

  @Test
  void startupPingWarn_unparseableValueKeepsTheDefaultButIsFlagged() {
    Properties sys = props("ntfy.startup-ping-warn", "prob");

    NtfyConfig config = ConfigLoader.load(null, null, sys);

    assertThat(config.getStartupPingWarn()).isEqualTo(StartupPingMode.OFF);
    assertThat(config.isStartupPingWarnValueRejected()).isTrue();
  }

  @Test
  void startupPingNotifyFailures_defaultsToOn() {
    assertThat(ConfigLoader.load(null, null, null).isStartupPingNotifyFailures()).isTrue();
  }

  @Test
  void startupPingNotifyFailures_canBeTurnedOff() {
    Properties sys = props("ntfy.startup-ping-notify-failures", "false");

    assertThat(ConfigLoader.load(null, null, sys).isStartupPingNotifyFailures()).isFalse();
  }

  @Test
  void startupPingFailFast_fromEnv() {
    Function<String, String> env = env(Map.of("NTFY_STARTUP_PING_FAIL_FAST", "true"));

    NtfyConfig config = ConfigLoader.load(env, null, null);

    assertThat(config.isStartupPingFailFast()).isTrue();
  }
}
