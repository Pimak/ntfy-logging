package io.github.pimak.ntfy.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, framework-neutral configuration for the ntfy engine and client. Built exclusively
 * through the fluent {@link Builder} ({@code NtfyConfig.builder()...build()}); every field is a
 * plain JDK type so adapters can populate it from Joran setters, Spring {@code @Configuration
 * Properties}, Quarkus {@code @ConfigMapping}, or {@link ConfigLoader} without core ever depending
 * on a framework.
 *
 * <p>Defaults are applied by the builder (not here) so a builder that is never touched still yields
 * a fully-populated config. {@code excludedLoggerPrefixes} is always stored unmodifiable.
 */
public final class NtfyConfig {

  private final String url;
  private final String topic;
  private final String token;
  private final String username;
  private final String password;
  private final String title;
  private final String appName;
  private final int maxStackFrames;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final int maxAlertsPerWindow;
  private final Duration suppressionWindow;
  private final String errorPriority;
  private final String digestPriority;
  private final String errorTags;
  private final String digestTags;
  private final String clickUrl;
  private final String actions;
  private final List<String> excludedLoggerPrefixes;
  private final boolean enabled;
  private final boolean asyncEnabled;
  private final int asyncQueueCapacity;
  private final boolean requireHttpsForCredentials;
  private final boolean endpointFromClasspathFile;

  private NtfyConfig(Builder b) {
    this.url = b.url;
    this.topic = b.topic;
    this.token = b.token;
    this.username = b.username;
    this.password = b.password;
    this.title = b.title;
    this.appName = b.appName;
    this.maxStackFrames = b.maxStackFrames;
    this.connectTimeout = b.connectTimeout;
    this.requestTimeout = b.requestTimeout;
    this.maxAlertsPerWindow = b.maxAlertsPerWindow;
    this.suppressionWindow = b.suppressionWindow;
    this.errorPriority = b.errorPriority;
    this.digestPriority = b.digestPriority;
    this.errorTags = b.errorTags;
    this.digestTags = b.digestTags;
    this.clickUrl = b.clickUrl;
    this.actions = b.actions;
    this.excludedLoggerPrefixes =
        Collections.unmodifiableList(new ArrayList<>(b.excludedLoggerPrefixes));
    this.enabled = b.enabled;
    this.asyncEnabled = b.asyncEnabled;
    this.asyncQueueCapacity = b.asyncQueueCapacity;
    this.requireHttpsForCredentials = b.requireHttpsForCredentials;
    this.endpointFromClasspathFile = b.endpointFromClasspathFile;
  }

  /** Returns a fresh {@link Builder} pre-loaded with every default. */
  public static Builder builder() {
    return new Builder();
  }

  public String getUrl() {
    return url;
  }

  public String getTopic() {
    return topic;
  }

  public String getToken() {
    return token;
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }

  public String getTitle() {
    return title;
  }

  public String getAppName() {
    return appName;
  }

  public int getMaxStackFrames() {
    return maxStackFrames;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public int getMaxAlertsPerWindow() {
    return maxAlertsPerWindow;
  }

  public Duration getSuppressionWindow() {
    return suppressionWindow;
  }

  public String getErrorPriority() {
    return errorPriority;
  }

  public String getDigestPriority() {
    return digestPriority;
  }

  public String getErrorTags() {
    return errorTags;
  }

  public String getDigestTags() {
    return digestTags;
  }

  /**
   * The URL ntfy opens when a notification is tapped (sent as the {@code Click} header), or {@code
   * null}/blank to send no {@code Click} header. Applies to both individual error alerts and the
   * storm digest.
   */
  public String getClickUrl() {
    return clickUrl;
  }

  /**
   * The pre-serialized ntfy {@code Actions} header value (action buttons) sent with both individual
   * error alerts and the storm digest, or {@code null}/blank to send no {@code Actions} header. Set
   * either as a raw ntfy short-format string ({@link Builder#actionsHeader(String)}) or from typed
   * {@link NtfyAction}s ({@link Builder#actions(List)}).
   */
  public String getActions() {
    return actions;
  }

  /** The unmodifiable list of logger-name prefixes excluded from alerting. */
  public List<String> getExcludedLoggerPrefixes() {
    return excludedLoggerPrefixes;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * True when delivery of individual error alerts should be offloaded to the engine's bounded
   * asynchronous work queue instead of running inline on the calling (logging) thread. Off by
   * default: with async disabled, delivery is synchronous and behavior is identical to before this
   * flag existed. When on, a slow or unreachable ntfy server can no longer back-pressure application
   * threads during an error storm — see {@link AlertEngine} for the queue/overflow semantics.
   */
  public boolean isAsyncEnabled() {
    return asyncEnabled;
  }

  /**
   * Maximum number of pending error alerts the asynchronous delivery queue holds before overflow
   * kicks in (see {@link AlertEngine}). Only consulted when {@link #isAsyncEnabled()} is true. A
   * non-positive value is clamped to a safe minimum by the engine at start.
   */
  public int getAsyncQueueCapacity() {
    return asyncQueueCapacity;
  }

  /**
   * True when the engine must REFUSE activation (rather than merely warn) if credentials — a
   * configured token, a username/password pair, or userinfo embedded in the URL itself — would be
   * sent over a cleartext {@code http://} endpoint. Off by default: without this opt-in, the engine
   * warns loudly but still activates, preserving pre-existing behavior for deliberate self-hosted
   * plain-HTTP setups.
   */
  public boolean isRequireHttpsForCredentials() {
    return requireHttpsForCredentials;
  }

  /**
   * True when the endpoint URL was supplied ONLY by a classpath {@code ntfy.properties} (no system
   * property or environment variable set it). Set by {@link ConfigLoader}; auto-installing
   * adapters use it to warn loudly, because any jar on the classpath can carry such a file and
   * silently redirect log-derived alerts to a server the operator never chose.
   */
  public boolean isEndpointFromClasspathFile() {
    return endpointFromClasspathFile;
  }

  /**
   * True when this config should actually deliver alerts: it is {@link #enabled}, and both {@link
   * #getUrl()} and {@link #getTopic()} are non-blank. A config that is disabled or missing either
   * endpoint half is inactive (the engine reports why via {@link Diagnostics} at start).
   */
  public boolean isActive() {
    return enabled && !isBlank(url) && !isBlank(topic);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Fluent builder for {@link NtfyConfig}; every setter returns {@code this}. */
  public static final class Builder {
    private String url;
    private String topic;
    private String token;
    private String username;
    private String password;
    private String title;
    private String appName;
    private int maxStackFrames = 5;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int maxAlertsPerWindow = 3;
    private Duration suppressionWindow = Duration.ofMinutes(3);
    private String errorPriority = "high";
    private String digestPriority = "urgent";
    private String errorTags = "rotating_light";
    private String digestTags = "fire";
    private String clickUrl;
    private String actions;
    private List<String> excludedLoggerPrefixes = new ArrayList<>();
    private boolean enabled = true;
    private boolean asyncEnabled = false;
    private int asyncQueueCapacity = 1024;
    private boolean requireHttpsForCredentials = false;
    private boolean endpointFromClasspathFile = false;

    private Builder() {}

    public Builder url(String url) {
      this.url = url;
      return this;
    }

    public Builder topic(String topic) {
      this.topic = topic;
      return this;
    }

    public Builder token(String token) {
      this.token = token;
      return this;
    }

    public Builder username(String username) {
      this.username = username;
      return this;
    }

    public Builder password(String password) {
      this.password = password;
      return this;
    }

    public Builder title(String title) {
      this.title = title;
      return this;
    }

    public Builder appName(String appName) {
      this.appName = appName;
      return this;
    }

    public Builder maxStackFrames(int maxStackFrames) {
      this.maxStackFrames = maxStackFrames;
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    public Builder maxAlertsPerWindow(int maxAlertsPerWindow) {
      this.maxAlertsPerWindow = maxAlertsPerWindow;
      return this;
    }

    public Builder suppressionWindow(Duration suppressionWindow) {
      this.suppressionWindow = suppressionWindow;
      return this;
    }

    public Builder errorPriority(String errorPriority) {
      this.errorPriority = errorPriority;
      return this;
    }

    public Builder digestPriority(String digestPriority) {
      this.digestPriority = digestPriority;
      return this;
    }

    public Builder errorTags(String errorTags) {
      this.errorTags = errorTags;
      return this;
    }

    public Builder digestTags(String digestTags) {
      this.digestTags = digestTags;
      return this;
    }

    /** URL opened when a notification is tapped (ntfy {@code Click} header); {@code null} to omit. */
    public Builder clickUrl(String clickUrl) {
      this.clickUrl = clickUrl;
      return this;
    }

    /**
     * Sets action buttons from a raw, pre-formatted ntfy {@code Actions} header value (the short
     * "simple" format, e.g. {@code "view, View logs, https://grafana.example.com/d/abc"}). Forwarded
     * verbatim; {@code null}/blank sends no {@code Actions} header. This is the flat-string entry
     * point used by environment/YAML/XML configuration.
     */
    public Builder actionsHeader(String actionsHeader) {
      this.actions = actionsHeader;
      return this;
    }

    /**
     * Sets action buttons from typed {@link NtfyAction}s, serialized to the ntfy {@code Actions}
     * header via {@link NtfyActionSerializer}. ntfy caps a notification at three actions; extra
     * entries and {@code null} elements are dropped. A {@code null}/empty list sends no {@code
     * Actions} header. Constructing an individual {@link NtfyAction} with a blank label/url throws.
     */
    public Builder actions(List<NtfyAction> actions) {
      this.actions = NtfyActionSerializer.serialize(actions);
      return this;
    }

    /** Sets the excluded logger prefixes from an explicit list (defensively copied, null-safe). */
    public Builder excludedLoggerPrefixes(List<String> prefixes) {
      this.excludedLoggerPrefixes =
          prefixes == null ? new ArrayList<>() : new ArrayList<>(prefixes);
      return this;
    }

    /**
     * Convenience: sets the excluded logger prefixes from a single comma-separated string, trimming
     * each entry and dropping blanks. A {@code null}/blank csv clears the list.
     */
    public Builder excludedLoggers(String csv) {
      List<String> prefixes = new ArrayList<>();
      if (csv != null && !csv.isBlank()) {
        for (String part : csv.split(",")) {
          String trimmed = part.trim();
          if (!trimmed.isEmpty()) {
            prefixes.add(trimmed);
          }
        }
      }
      this.excludedLoggerPrefixes = prefixes;
      return this;
    }

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Opt into asynchronous (offloaded) delivery of individual error alerts. Off by default; see
     * {@link NtfyConfig#isAsyncEnabled()}.
     */
    public Builder asyncEnabled(boolean asyncEnabled) {
      this.asyncEnabled = asyncEnabled;
      return this;
    }

    /**
     * Sets the bounded async delivery queue capacity (see {@link NtfyConfig#getAsyncQueueCapacity()}).
     * A non-positive value is clamped to a safe minimum by the engine at start.
     */
    public Builder asyncQueueCapacity(int asyncQueueCapacity) {
      this.asyncQueueCapacity = asyncQueueCapacity;
      return this;
    }

    /**
     * Opt into refusing activation when credentials would traverse a cleartext {@code http://}
     * endpoint. Off by default (warn-and-activate); see
     * {@link NtfyConfig#isRequireHttpsForCredentials()}.
     */
    public Builder requireHttpsForCredentials(boolean requireHttpsForCredentials) {
      this.requireHttpsForCredentials = requireHttpsForCredentials;
      return this;
    }

    /** See {@link NtfyConfig#isEndpointFromClasspathFile()}; set by {@link ConfigLoader} only. */
    public Builder endpointFromClasspathFile(boolean endpointFromClasspathFile) {
      this.endpointFromClasspathFile = endpointFromClasspathFile;
      return this;
    }

    public NtfyConfig build() {
      return new NtfyConfig(this);
    }
  }
}
