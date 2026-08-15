package io.github.pimak.ntfy.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds all {@code ntfy.*} application properties. Spring Boot's relaxed binding maps kebab-case
 * (e.g. {@code ntfy.app-name}), camelCase and underscore variants onto these fields, and binds the
 * {@link Duration} fields natively from strings like {@code 5s}, {@code 3m}, {@code 500ms} or a bare
 * number interpreted as milliseconds.
 *
 * <p>The javadoc on each field below is not decoration: {@code spring-boot-configuration-processor}
 * reads it (the field's, never the getter's) to fill the {@code description} of every key in the
 * generated {@code META-INF/spring-configuration-metadata.json}, which is what an IDE shows while
 * completing {@code ntfy.*} in {@code application.yml}. It is therefore the single source of truth
 * for those descriptions — {@code additional-spring-configuration-metadata.json} carries only what
 * the processor cannot infer from the source (non-literal defaults, completion hints), and
 * {@code NtfyRuntimeConfig} in the Quarkus extension is the mirror of this class for
 * {@code quarkus.ntfy.*}. Keep the three in step.
 */
@ConfigurationProperties(prefix = "ntfy")
public class NtfyProperties {

  /**
   * Base URL of the ntfy server, e.g. {@code https://ntfy.example.com}. Alerting stays inactive
   * until both {@code url} and {@code topic} are set.
   */
  private String url;

  /**
   * Topic to publish to. Must match ntfy's topic rule {@code [-_A-Za-z0-9]{1,64}} — anything else
   * disables the engine with a warning. Alerting stays inactive until both {@code url} and
   * {@code topic} are set.
   */
  private String topic;

  /** Bearer/access token (ntfy {@code tk_...}); takes precedence over username/password. */
  private String token;

  /** HTTP Basic username, used with {@code password} when no {@code token} is set. */
  private String username;

  /** HTTP Basic password. */
  private String password;

  /**
   * Notification title prefix; the root-cause exception class is appended for error alerts. Falls
   * back to {@code app-name} when unset.
   */
  private String title;

  /** Application name used as the title base when {@code title} is unset. */
  private String appName;

  /** Maximum root-cause stack frames rendered into an alert body. */
  private int maxStackFrames = 5;

  /** HTTP connect timeout. */
  private Duration connectTimeout = Duration.ofSeconds(5);

  /** HTTP request/response round-trip timeout. */
  private Duration requestTimeout = Duration.ofSeconds(10);

  /**
   * Individual alerts allowed per suppression window before storm rate-limiting kicks in; zero or
   * negative disables the limiter.
   */
  private int maxAlertsPerWindow = 3;

  /** Rolling window for the burst allowance and the periodic digest timer. */
  private Duration suppressionWindow = Duration.ofMinutes(3);

  /** ntfy {@code Priority} header for individual error alerts. */
  private String errorPriority = "high";

  /** ntfy {@code Priority} header for storm digests. */
  private String digestPriority = "urgent";

  /** ntfy {@code Tags} header (comma-separated) for individual error alerts. */
  private String errorTags = "rotating_light";

  /** ntfy {@code Tags} header (comma-separated) for storm digests. */
  private String digestTags = "fire";

  /**
   * URL ntfy opens when the notification is tapped (ntfy {@code Click} header). Applies to error
   * alerts and digests alike; no header is sent when unset.
   */
  private String clickUrl;

  /**
   * Action buttons as a raw ntfy {@code Actions} header value in the short format (e.g.
   * {@code view, View logs, https://grafana.example.com/d/abc}; up to 3, separated by {@code ;}).
   * Applies to error alerts and digests alike; no header is sent when unset.
   */
  private String actions;

  /**
   * Language of notification bodies and diagnostics as a BCP 47 tag (e.g. {@code fr}, {@code
   * de-DE}). Defaults to English; an unknown/unshipped locale silently uses English.
   */
  private String locale;

  /** Comma-separated logger names or prefixes whose events are never published. */
  private String excludedLoggers;

  /**
   * Whether the ntfy integration is active. When true, the appender is installed on the bound
   * logging backend (Logback or Log4j2) and the NtfyClient bean is created.
   */
  private boolean enabled = true;

  /**
   * Asynchronous (offloaded) delivery: alerts are handed to a bounded queue drained by a daemon
   * worker, so a slow or unreachable ntfy server never blocks the logging thread. On by default;
   * set {@code false} for pre-2.0 synchronous, inline delivery.
   */
  private boolean async = true;

  /**
   * Bounded async delivery queue capacity; overflowing alerts fold into the storm digest count.
   * Only consulted when {@code async} is {@code true}; a non-positive value is clamped to 1.
   */
  private int asyncQueueCapacity = 1024;

  /**
   * Strict mode: refuse to activate alerting (instead of only warning) when credentials would be
   * sent over a plain http:// endpoint. Set to false to restore the pre-2.0 warn-and-activate
   * behavior.
   */
  private boolean requireHttpsForCredentials = true;

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public int getMaxStackFrames() {
    return maxStackFrames;
  }

  public void setMaxStackFrames(int maxStackFrames) {
    this.maxStackFrames = maxStackFrames;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public int getMaxAlertsPerWindow() {
    return maxAlertsPerWindow;
  }

  public void setMaxAlertsPerWindow(int maxAlertsPerWindow) {
    this.maxAlertsPerWindow = maxAlertsPerWindow;
  }

  public Duration getSuppressionWindow() {
    return suppressionWindow;
  }

  public void setSuppressionWindow(Duration suppressionWindow) {
    this.suppressionWindow = suppressionWindow;
  }

  public String getErrorPriority() {
    return errorPriority;
  }

  public void setErrorPriority(String errorPriority) {
    this.errorPriority = errorPriority;
  }

  public String getDigestPriority() {
    return digestPriority;
  }

  public void setDigestPriority(String digestPriority) {
    this.digestPriority = digestPriority;
  }

  public String getErrorTags() {
    return errorTags;
  }

  public void setErrorTags(String errorTags) {
    this.errorTags = errorTags;
  }

  public String getDigestTags() {
    return digestTags;
  }

  public void setDigestTags(String digestTags) {
    this.digestTags = digestTags;
  }

  public String getClickUrl() {
    return clickUrl;
  }

  public void setClickUrl(String clickUrl) {
    this.clickUrl = clickUrl;
  }

  public String getActions() {
    return actions;
  }

  public void setActions(String actions) {
    this.actions = actions;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public String getExcludedLoggers() {
    return excludedLoggers;
  }

  public void setExcludedLoggers(String excludedLoggers) {
    this.excludedLoggers = excludedLoggers;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isAsync() {
    return async;
  }

  public void setAsync(boolean async) {
    this.async = async;
  }

  public int getAsyncQueueCapacity() {
    return asyncQueueCapacity;
  }

  public void setAsyncQueueCapacity(int asyncQueueCapacity) {
    this.asyncQueueCapacity = asyncQueueCapacity;
  }

  public boolean isRequireHttpsForCredentials() {
    return requireHttpsForCredentials;
  }

  public void setRequireHttpsForCredentials(boolean requireHttpsForCredentials) {
    this.requireHttpsForCredentials = requireHttpsForCredentials;
  }
}
