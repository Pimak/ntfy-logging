package io.github.pimak.ntfy.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/**
 * Resolves an {@link NtfyConfig} from the ambient environment. This is the ONLY class in ntfy-core
 * permitted to read {@link System#getenv} — every other class receives its configuration through an
 * already-built {@link NtfyConfig}, keeping environment access to a single sanctioned reader.
 *
 * <p>Each field is resolved by precedence, highest first:
 *
 * <ol>
 *   <li>JVM system property {@code ntfy.<kebab-key>} (e.g. {@code -Dntfy.app-name=svc});
 *   <li>environment variable {@code NTFY_<UPPER_SNAKE_KEY>} (e.g. {@code NTFY_APP_NAME});
 *   <li>classpath {@code ntfy.properties} entry {@code ntfy.<kebab-key>};
 *   <li>the built-in default carried by {@link NtfyConfig.Builder}.
 * </ol>
 *
 * <p><strong>Exception:</strong> {@code allow-classpath-endpoint} is deliberately resolved from the
 * system-property and environment layers only — never the classpath {@code ntfy.properties} file —
 * so a file shipped on the classpath can never grant itself the trust to auto-activate alerting
 * (see {@link #load()}).
 */
public final class ConfigLoader {

  private static final String PROPERTIES_RESOURCE = "ntfy.properties";

  private ConfigLoader() {}

  /**
   * Loads configuration from live system properties, environment variables, and a classpath {@code
   * ntfy.properties} (if present), applying the precedence documented on this class.
   */
  public static NtfyConfig load() {
    return load(System::getenv, loadClasspathProperties(), System.getProperties());
  }

  /**
   * Test/seam overload: resolves against the supplied lookups instead of the live JVM environment,
   * so unit tests can inject an env map, a properties file, and system properties deterministically.
   * Any argument may be {@code null} to skip that layer.
   */
  static NtfyConfig load(
      Function<String, String> envLookup, Properties fileProps, Properties sysProps) {
    NtfyConfig.Builder builder = NtfyConfig.builder();

    String url = resolve("url", envLookup, fileProps, sysProps);
    apply(builder::url, url);
    // Flag an endpoint URL that only the classpath-file layer supplied: any jar can ship a
    // ntfy.properties, and an auto-installing adapter must be able to warn that alert delivery
    // was activated by classpath content rather than by the operator's env/sysprops.
    if (url != null && resolve("url", envLookup, null, sysProps) == null) {
      builder.endpointFromClasspathFile(true);
    }
    apply(builder::topic, resolve("topic", envLookup, fileProps, sysProps));
    apply(builder::token, resolve("token", envLookup, fileProps, sysProps));
    apply(builder::username, resolve("username", envLookup, fileProps, sysProps));
    apply(builder::password, resolve("password", envLookup, fileProps, sysProps));
    apply(builder::title, resolve("title", envLookup, fileProps, sysProps));
    apply(builder::appName, resolve("app-name", envLookup, fileProps, sysProps));
    apply(builder::errorPriority, resolve("error-priority", envLookup, fileProps, sysProps));
    apply(builder::digestPriority, resolve("digest-priority", envLookup, fileProps, sysProps));
    apply(builder::errorTags, resolve("error-tags", envLookup, fileProps, sysProps));
    apply(builder::digestTags, resolve("digest-tags", envLookup, fileProps, sysProps));
    apply(builder::clickUrl, resolve("click-url", envLookup, fileProps, sysProps));
    apply(builder::actionsHeader, resolve("actions", envLookup, fileProps, sysProps));

    String excludedLoggers = resolve("excluded-loggers", envLookup, fileProps, sysProps);
    if (excludedLoggers != null) {
      builder.excludedLoggers(excludedLoggers);
    }

    applyInt(builder::maxStackFrames, resolve("max-stack-frames", envLookup, fileProps, sysProps));
    applyInt(
        builder::maxAlertsPerWindow,
        resolve("max-alerts-per-window", envLookup, fileProps, sysProps));

    applyDuration(
        builder::connectTimeout, resolve("connect-timeout", envLookup, fileProps, sysProps));
    applyDuration(
        builder::requestTimeout, resolve("request-timeout", envLookup, fileProps, sysProps));
    applyDuration(
        builder::suppressionWindow,
        resolve("suppression-window", envLookup, fileProps, sysProps));

    String enabled = resolve("enabled", envLookup, fileProps, sysProps);
    if (enabled != null) {
      builder.enabled(Boolean.parseBoolean(enabled.trim()));
    }

    // Deliberately resolved WITHOUT the classpath-file layer: this flag is the operator's opt-in
    // for trusting a classpath-supplied endpoint, so a jar shipping ntfy.properties must never be
    // able to grant that trust to itself. Only a system property or env var can set it.
    String allowClasspathEndpoint =
        resolve("allow-classpath-endpoint", envLookup, null, sysProps);
    if (allowClasspathEndpoint != null) {
      builder.allowClasspathEndpoint(Boolean.parseBoolean(allowClasspathEndpoint.trim()));
    }

    String async = resolve("async", envLookup, fileProps, sysProps);
    if (async != null) {
      builder.asyncEnabled(Boolean.parseBoolean(async.trim()));
    }
    applyInt(
        builder::asyncQueueCapacity,
        resolve("async-queue-capacity", envLookup, fileProps, sysProps));

    String requireHttps =
        resolve("require-https-for-credentials", envLookup, fileProps, sysProps);
    if (requireHttps != null) {
      builder.requireHttpsForCredentials(Boolean.parseBoolean(requireHttps.trim()));
    }

    return builder.build();
  }

  /**
   * Resolves a single kebab-key against the precedence chain: system property {@code ntfy.<key>},
   * then env {@code NTFY_<KEY>}, then file property {@code ntfy.<key>}. Blank values at any layer
   * are treated as absent so a layer never masks a lower one with an empty string. Returns {@code
   * null} when no layer supplies a value (the builder default then stands).
   */
  private static String resolve(
      String kebabKey, Function<String, String> env, Properties file, Properties sys) {
    String propKey = "ntfy." + kebabKey;

    if (sys != null) {
      String fromSys = sys.getProperty(propKey);
      if (!isBlank(fromSys)) {
        return fromSys;
      }
    }
    if (env != null) {
      String envKey = "NTFY_" + kebabKey.toUpperCase(Locale.ROOT).replace('-', '_');
      String fromEnv = env.apply(envKey);
      if (!isBlank(fromEnv)) {
        return fromEnv;
      }
    }
    if (file != null) {
      String fromFile = file.getProperty(propKey);
      if (!isBlank(fromFile)) {
        return fromFile;
      }
    }
    return null;
  }

  private static void apply(java.util.function.Consumer<String> setter, String value) {
    if (value != null) {
      setter.accept(value.trim());
    }
  }

  private static void applyInt(java.util.function.IntConsumer setter, String value) {
    if (value != null) {
      try {
        setter.accept(Integer.parseInt(value.trim()));
      } catch (NumberFormatException e) {
        // A malformed value (e.g. NTFY_MAX_STACK_FRAMES=abc) keeps the builder default instead of
        // throwing out of load() — an unparsable optional tuning knob must never break the
        // logging framework's own initialization inside an adapter.
      }
    }
  }

  private static void applyDuration(
      java.util.function.Consumer<java.time.Duration> setter, String value) {
    if (value != null) {
      try {
        setter.accept(DurationParser.parse(value));
      } catch (IllegalArgumentException e) {
        // Same rationale as applyInt: keep the default rather than fail logging init.
      }
    }
  }

  /**
   * Loads {@code ntfy.properties} from the classpath if present; an absent file (or any read error)
   * yields empty properties, so classpath configuration is strictly optional.
   */
  private static Properties loadClasspathProperties() {
    Properties props = new Properties();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = ConfigLoader.class.getClassLoader();
    }
    try (InputStream in = loader.getResourceAsStream(PROPERTIES_RESOURCE)) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException e) {
      // Optional file — a read failure is not fatal; defaults and higher-precedence layers apply.
    }
    return props;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
