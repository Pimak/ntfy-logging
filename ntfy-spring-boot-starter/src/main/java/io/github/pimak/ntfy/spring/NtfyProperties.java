package io.github.pimak.ntfy.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds all {@code ntfy.*} application properties. Spring Boot's relaxed binding maps kebab-case
 * (e.g. {@code ntfy.app-name}), camelCase and underscore variants onto these fields, and binds the
 * {@link Duration} fields natively from strings like {@code 5s}, {@code 3m}, {@code 500ms} or a bare
 * number interpreted as milliseconds.
 */
@ConfigurationProperties(prefix = "ntfy")
public class NtfyProperties {

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
  private String warnTopic;
  private String warnPriority = "default";
  private String warnTags = "warning";
  private String clickUrl;
  private String actions;
  private String locale;
  private String excludedLoggers;

  /**
   * Comma-separated fully qualified exception class names. An event whose cause chain contains
   * any of them never alerts — matched anywhere in the chain, not just on the surface
   * throwable.
   */
  private String excludedExceptionTypes;
  private String includeMdcKeys;
  private boolean enabled = true;
  private boolean async = true;
  private int asyncQueueCapacity = 1024;
  private boolean requireHttpsForCredentials = true;
  private String startupPing = "off";
  private String startupPingWarn = "off";
  private boolean startupPingFailFast = false;
  private boolean startupPingNotifyFailures = true;

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

  /**
   * The topic WARN-level events are published to. A non-blank value is the entire opt-in for WARN
   * alerting: unset (the default), alerting stays ERROR-only. Set it to the same value as {@code
   * ntfy.topic} to alert on warnings through the main topic at a different priority.
   */
  public String getWarnTopic() {
    return warnTopic;
  }

  public void setWarnTopic(String warnTopic) {
    this.warnTopic = warnTopic;
  }

  /** ntfy {@code Priority} header for WARN alerts and the WARN digest. */
  public String getWarnPriority() {
    return warnPriority;
  }

  public void setWarnPriority(String warnPriority) {
    this.warnPriority = warnPriority;
  }

  /** ntfy {@code Tags} header for WARN alerts and the WARN digest. */
  public String getWarnTags() {
    return warnTags;
  }

  public void setWarnTags(String warnTags) {
    this.warnTags = warnTags;
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

  /**
   * Language of notification bodies and diagnostics as a BCP 47 tag (e.g. {@code fr}, {@code
   * de-DE}). Defaults to English; an unknown/unshipped locale silently uses English.
   */
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

  public String getExcludedExceptionTypes() {
    return excludedExceptionTypes;
  }

  public void setExcludedExceptionTypes(String excludedExceptionTypes) {
    this.excludedExceptionTypes = excludedExceptionTypes;
  }

  /**
   * Comma-separated allow-list of MDC keys whose values are rendered into alert bodies, one {@code
   * key: value} line each, in the order given here. Empty by default, and there is deliberately no
   * wildcard form: an MDC value reaches a notification only when an operator named its key, because
   * a production MDC routinely holds tokens and user identifiers that must never leave the process.
   */
  public String getIncludeMdcKeys() {
    return includeMdcKeys;
  }

  public void setIncludeMdcKeys(String includeMdcKeys) {
    this.includeMdcKeys = includeMdcKeys;
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

  public String getStartupPing() {
    return startupPing;
  }

  /**
   * Bound as a {@code String} rather than the {@code StartupPingMode} enum on purpose: Spring's
   * relaxed binder throws on an unconvertible enum value, which would turn one typo in
   * {@code application.yml} into a failed context refresh. The core parses it leniently and warns
   * instead, matching every other surface.
   */
  public void setStartupPing(String startupPing) {
    this.startupPing = startupPing;
  }

  public boolean isStartupPingFailFast() {
    return startupPingFailFast;
  }

  public void setStartupPingFailFast(boolean startupPingFailFast) {
    this.startupPingFailFast = startupPingFailFast;
  }

  public String getStartupPingWarn() {
    return startupPingWarn;
  }

  /** Bound as a {@code String} for the same reason as {@link #setStartupPing(String)}. */
  public void setStartupPingWarn(String startupPingWarn) {
    this.startupPingWarn = startupPingWarn;
  }

  public boolean isStartupPingNotifyFailures() {
    return startupPingNotifyFailures;
  }

  public void setStartupPingNotifyFailures(boolean startupPingNotifyFailures) {
    this.startupPingNotifyFailures = startupPingNotifyFailures;
  }
}
