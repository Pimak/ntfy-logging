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
  private String excludedLoggers;
  private boolean enabled = true;

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
}
