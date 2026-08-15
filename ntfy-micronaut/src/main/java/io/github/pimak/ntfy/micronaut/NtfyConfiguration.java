package io.github.pimak.ntfy.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/**
 * Binds all {@code ntfy.*} application configuration for a Micronaut application. Micronaut's
 * relaxed binding maps kebab-case (e.g. {@code ntfy.app-name}), camelCase and underscore variants
 * onto these fields, and converts the {@link Duration} fields natively from strings such as {@code
 * 5s}, {@code 3m}, {@code 500ms} or an ISO-8601 value like {@code PT10S}.
 *
 * <p>Key names, types and defaults are deliberately identical to the Spring starter's {@code
 * NtfyProperties}, so the same {@code ntfy.*} block can be lifted from an {@code application.yml}
 * of one framework into the other. Notably {@code async} and {@code require-https-for-credentials}
 * both default to {@code true}, matching the engine defaults.
 *
 * <p>Getters are plain accessors and are intentionally undocumented one by one; the authoritative
 * description of every knob lives in the project README's configuration reference.
 */
@ConfigurationProperties("ntfy")
public class NtfyConfiguration {

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
  private String locale;
  private String excludedLoggers;
  private boolean enabled = true;
  private boolean async = true;
  private int asyncQueueCapacity = 1024;
  private boolean requireHttpsForCredentials = true;

  /**
   * Base URL of the ntfy server, e.g. {@code https://ntfy.sh}. Alerting stays off until both this
   * and {@link #getTopic()} are set.
   *
   * @return the configured server URL, or {@code null} when unset
   */
  public String getUrl() {
    return url;
  }

  /**
   * Sets the ntfy server base URL.
   *
   * @param url the server URL
   */
  public void setUrl(String url) {
    this.url = url;
  }

  /**
   * Topic notifications are published to.
   *
   * @return the topic, or {@code null} when unset
   */
  public String getTopic() {
    return topic;
  }

  /**
   * Sets the ntfy topic.
   *
   * @param topic the topic name
   */
  public void setTopic(String topic) {
    this.topic = topic;
  }

  /**
   * Bearer access token used instead of username/password authentication.
   *
   * @return the token, or {@code null} when unset
   */
  public String getToken() {
    return token;
  }

  /**
   * Sets the bearer access token.
   *
   * @param token the access token
   */
  public void setToken(String token) {
    this.token = token;
  }

  /**
   * Username for basic authentication.
   *
   * @return the username, or {@code null} when unset
   */
  public String getUsername() {
    return username;
  }

  /**
   * Sets the basic-authentication username.
   *
   * @param username the username
   */
  public void setUsername(String username) {
    this.username = username;
  }

  /**
   * Password for basic authentication.
   *
   * @return the password, or {@code null} when unset
   */
  public String getPassword() {
    return password;
  }

  /**
   * Sets the basic-authentication password.
   *
   * @param password the password
   */
  public void setPassword(String password) {
    this.password = password;
  }

  /**
   * Fixed notification title; when unset the engine derives one from the event.
   *
   * @return the title, or {@code null} when unset
   */
  public String getTitle() {
    return title;
  }

  /**
   * Sets the fixed notification title.
   *
   * @param title the title
   */
  public void setTitle(String title) {
    this.title = title;
  }

  /**
   * Application name shown in notification bodies to distinguish services sharing a topic.
   *
   * @return the application name, or {@code null} when unset
   */
  public String getAppName() {
    return appName;
  }

  /**
   * Sets the application name.
   *
   * @param appName the application name
   */
  public void setAppName(String appName) {
    this.appName = appName;
  }

  /**
   * Number of stack frames included in a notification body.
   *
   * @return the frame count (default {@code 5})
   */
  public int getMaxStackFrames() {
    return maxStackFrames;
  }

  /**
   * Sets the number of stack frames included in a notification body.
   *
   * @param maxStackFrames the frame count
   */
  public void setMaxStackFrames(int maxStackFrames) {
    this.maxStackFrames = maxStackFrames;
  }

  /**
   * HTTP connect timeout for publishing.
   *
   * @return the connect timeout (default 5 seconds)
   */
  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * Sets the HTTP connect timeout.
   *
   * @param connectTimeout the connect timeout
   */
  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  /**
   * HTTP request timeout for publishing.
   *
   * @return the request timeout (default 10 seconds)
   */
  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  /**
   * Sets the HTTP request timeout.
   *
   * @param requestTimeout the request timeout
   */
  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  /**
   * Individual alerts published per suppression window before the engine switches to digesting.
   *
   * @return the per-window alert budget (default {@code 3})
   */
  public int getMaxAlertsPerWindow() {
    return maxAlertsPerWindow;
  }

  /**
   * Sets the per-window alert budget.
   *
   * @param maxAlertsPerWindow the alert budget
   */
  public void setMaxAlertsPerWindow(int maxAlertsPerWindow) {
    this.maxAlertsPerWindow = maxAlertsPerWindow;
  }

  /**
   * Length of the storm-suppression window.
   *
   * @return the suppression window (default 3 minutes)
   */
  public Duration getSuppressionWindow() {
    return suppressionWindow;
  }

  /**
   * Sets the storm-suppression window.
   *
   * @param suppressionWindow the suppression window
   */
  public void setSuppressionWindow(Duration suppressionWindow) {
    this.suppressionWindow = suppressionWindow;
  }

  /**
   * ntfy priority of individual error notifications.
   *
   * @return the error priority (default {@code high})
   */
  public String getErrorPriority() {
    return errorPriority;
  }

  /**
   * Sets the priority of individual error notifications.
   *
   * @param errorPriority the ntfy priority
   */
  public void setErrorPriority(String errorPriority) {
    this.errorPriority = errorPriority;
  }

  /**
   * ntfy priority of digest notifications.
   *
   * @return the digest priority (default {@code urgent})
   */
  public String getDigestPriority() {
    return digestPriority;
  }

  /**
   * Sets the priority of digest notifications.
   *
   * @param digestPriority the ntfy priority
   */
  public void setDigestPriority(String digestPriority) {
    this.digestPriority = digestPriority;
  }

  /**
   * Comma-separated ntfy tags attached to individual error notifications.
   *
   * @return the error tags (default {@code rotating_light})
   */
  public String getErrorTags() {
    return errorTags;
  }

  /**
   * Sets the tags attached to individual error notifications.
   *
   * @param errorTags comma-separated ntfy tags
   */
  public void setErrorTags(String errorTags) {
    this.errorTags = errorTags;
  }

  /**
   * Comma-separated ntfy tags attached to digest notifications.
   *
   * @return the digest tags (default {@code fire})
   */
  public String getDigestTags() {
    return digestTags;
  }

  /**
   * Sets the tags attached to digest notifications.
   *
   * @param digestTags comma-separated ntfy tags
   */
  public void setDigestTags(String digestTags) {
    this.digestTags = digestTags;
  }

  /**
   * URL opened when a notification is tapped (ntfy {@code Click} header).
   *
   * @return the click URL, or {@code null} when unset
   */
  public String getClickUrl() {
    return clickUrl;
  }

  /**
   * Sets the click-through URL.
   *
   * @param clickUrl the URL opened when a notification is tapped
   */
  public void setClickUrl(String clickUrl) {
    this.clickUrl = clickUrl;
  }

  /**
   * Action buttons as a raw ntfy {@code Actions} header value (short format, e.g. {@code "view,
   * View logs, https://grafana.example.com/d/abc"}).
   *
   * @return the actions header value, or {@code null} when unset
   */
  public String getActions() {
    return actions;
  }

  /**
   * Sets the raw ntfy {@code Actions} header value.
   *
   * @param actions the actions header value
   */
  public void setActions(String actions) {
    this.actions = actions;
  }

  /**
   * Language of notification bodies and diagnostics as a BCP 47 tag (e.g. {@code fr}, {@code
   * de-DE}). Defaults to English; an unknown/unshipped locale silently uses English.
   *
   * @return the language tag, or {@code null} when unset
   */
  public String getLocale() {
    return locale;
  }

  /**
   * Sets the notification language as a BCP 47 tag.
   *
   * @param locale the language tag
   */
  public void setLocale(String locale) {
    this.locale = locale;
  }

  /**
   * Comma-separated logger-name prefixes excluded from alerting.
   *
   * @return the excluded prefixes, or {@code null} when unset
   */
  public String getExcludedLoggers() {
    return excludedLoggers;
  }

  /**
   * Sets the comma-separated logger-name prefixes excluded from alerting.
   *
   * @param excludedLoggers the excluded prefixes
   */
  public void setExcludedLoggers(String excludedLoggers) {
    this.excludedLoggers = excludedLoggers;
  }

  /**
   * Master switch for the integration. When {@code false} no appender is installed and the {@link
   * io.github.pimak.ntfy.core.NtfyClient} bean — still injectable — is built from an inactive
   * configuration.
   *
   * @return whether ntfy alerting is enabled (default {@code true})
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Enables or disables the integration.
   *
   * @param enabled whether ntfy alerting is enabled
   */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Asynchronous (offloaded) delivery, so a slow or unreachable ntfy server never blocks the
   * logging thread.
   *
   * @return whether async delivery is on (default {@code true})
   */
  public boolean isAsync() {
    return async;
  }

  /**
   * Turns asynchronous delivery on or off.
   *
   * @param async whether async delivery is on
   */
  public void setAsync(boolean async) {
    this.async = async;
  }

  /**
   * Bounded async delivery queue capacity; only consulted when {@link #isAsync()} is {@code true}.
   *
   * @return the queue capacity (default {@code 1024})
   */
  public int getAsyncQueueCapacity() {
    return asyncQueueCapacity;
  }

  /**
   * Sets the bounded async delivery queue capacity.
   *
   * @param asyncQueueCapacity the queue capacity
   */
  public void setAsyncQueueCapacity(int asyncQueueCapacity) {
    this.asyncQueueCapacity = asyncQueueCapacity;
  }

  /**
   * Strict transport mode: refuse engine activation (rather than only warn) when credentials would
   * be sent over a cleartext {@code http://} endpoint.
   *
   * @return whether HTTPS is required for credentials (default {@code true})
   */
  public boolean isRequireHttpsForCredentials() {
    return requireHttpsForCredentials;
  }

  /**
   * Turns the strict credentials-over-HTTPS requirement on or off.
   *
   * @param requireHttpsForCredentials whether HTTPS is required for credentials
   */
  public void setRequireHttpsForCredentials(boolean requireHttpsForCredentials) {
    this.requireHttpsForCredentials = requireHttpsForCredentials;
  }
}
