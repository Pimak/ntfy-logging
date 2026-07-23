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
  void errorPriority_isWiredThrough() {
    Function<String, String> env = env(Map.of("NTFY_ERROR_PRIORITY", "max"));

    assertThat(ConfigLoader.load(env, null, null).getErrorPriority()).isEqualTo("max");
  }

  @Test
  void requireHttpsForCredentials_defaultsToFalse() {
    assertThat(ConfigLoader.load(k -> null, null, null).isRequireHttpsForCredentials()).isFalse();
  }

  @Test
  void requireHttpsForCredentials_isWiredThroughFromEnv() {
    Function<String, String> env =
        env(Map.of("NTFY_REQUIRE_HTTPS_FOR_CREDENTIALS", "true"));

    assertThat(ConfigLoader.load(env, null, null).isRequireHttpsForCredentials()).isTrue();
  }

  @Test
  void requireHttpsForCredentials_isWiredThroughFromSystemProperty() {
    Properties sys = new Properties();
    sys.setProperty("ntfy.require-https-for-credentials", "true");

    assertThat(
            ConfigLoader.load(k -> null, null, sys).isRequireHttpsForCredentials())
        .isTrue();
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
}
