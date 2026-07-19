package io.github.pimak.ntfy.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.DurationParser;
import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * A thin Logback {@code UnsynchronizedAppenderBase<ILoggingEvent>} shell over the framework-neutral
 * {@link AlertEngine}. Configuration arrives either through JavaBean setters (Joran/XML config) or
 * through a pre-built {@link NtfyConfig} injected via {@link #setConfig(NtfyConfig)} by the
 * auto-installing {@code NtfyLogbackConfigurator}. Every decision that matters — logger exclusion
 * (including this library's own package), the {@code NO_ALERT} marker gate, storm rate-limiting,
 * digesting, payload assembly, and HTTP publishing — lives in the engine; this class only maps a
 * Logback event onto an {@code AlertEvent} and calls {@link AlertEngine#submit}.
 *
 * <p>Self-diagnoses exclusively through the inherited StatusManager methods (via {@link
 * LogbackDiagnostics}), so no SLF4J feedback loop is possible and no credential ever appears in
 * diagnostic output.
 */
public class LogbackAlertAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  /**
   * Marker name a caller attaches to a single log call to opt it out of alerting entirely. Re-exposed
   * from {@link AlertEngine#NO_ALERT_MARKER_NAME} so existing callers keep a stable handle instead of
   * a magic string.
   */
  public static final String NO_ALERT_MARKER_NAME = AlertEngine.NO_ALERT_MARKER_NAME;

  // Raw setter-supplied values. Duration fields are kept as Strings and parsed in start() via
  // DurationParser (bare int = ms, Nms/Ns/Nm/Nh/Nd, or ISO-8601) — no ch.qos.logback.core.util.Duration.
  private String url;
  private String topic;
  private String token;
  private String username;
  private String password;
  private String title;
  private String appName;
  private String excludedLoggers;
  private String connectTimeout;
  private String requestTimeout;
  private String suppressionWindow;
  private String errorPriority;
  private String digestPriority;
  private String errorTags;
  private String digestTags;
  private String clickUrl;
  private String actions;
  private Integer maxStackFrames;
  private Integer maxAlertsPerWindow;
  private Boolean enabled;

  // Pre-built config injected by the Configurator; when present it wins over the setters.
  private NtfyConfig injectedConfig;

  private AlertEngine engine;

  public void setUrl(String url) {
    this.url = url;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public void setMaxStackFrames(int maxStackFrames) {
    this.maxStackFrames = maxStackFrames;
  }

  /** Accepts a duration string (bare int = ms, {@code Nms/Ns/Nm/Nh/Nd}, or ISO-8601). */
  public void setConnectTimeout(String connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  /** Accepts a duration string (bare int = ms, {@code Nms/Ns/Nm/Nh/Nd}, or ISO-8601). */
  public void setRequestTimeout(String requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public void setMaxAlertsPerWindow(int maxAlertsPerWindow) {
    this.maxAlertsPerWindow = maxAlertsPerWindow;
  }

  /** Accepts a duration string (bare int = ms, {@code Nms/Ns/Nm/Nh/Nd}, or ISO-8601). */
  public void setSuppressionWindow(String suppressionWindow) {
    this.suppressionWindow = suppressionWindow;
  }

  public void setErrorPriority(String errorPriority) {
    this.errorPriority = errorPriority;
  }

  public void setDigestPriority(String digestPriority) {
    this.digestPriority = digestPriority;
  }

  public void setErrorTags(String errorTags) {
    this.errorTags = errorTags;
  }

  public void setDigestTags(String digestTags) {
    this.digestTags = digestTags;
  }

  /** URL opened when a notification is tapped (ntfy {@code Click} header). */
  public void setClickUrl(String clickUrl) {
    this.clickUrl = clickUrl;
  }

  /**
   * Action buttons as a raw ntfy {@code Actions} header value (short format, e.g. {@code "view, View
   * logs, https://grafana.example.com/d/abc"}).
   */
  public void setActions(String actions) {
    this.actions = actions;
  }

  /** A single comma-separated value of logger-name prefixes to exclude from alerting. */
  public void setExcludedLoggers(String excludedLoggers) {
    this.excludedLoggers = excludedLoggers;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Injects a pre-built {@link NtfyConfig} (bypassing the JavaBean setters). Used by the
   * auto-installing {@code NtfyLogbackConfigurator}, which loads its config from the ambient
   * environment via {@code ConfigLoader} rather than from XML.
   */
  void setConfig(NtfyConfig config) {
    this.injectedConfig = config;
  }

  /**
   * Builds an {@link AlertEngine} from the injected config (if present) or from the JavaBean
   * setters, starts it, and marks this appender started ONLY if the engine actually activated. A
   * silently/partially unconfigured engine leaves the appender {@code isStarted()==false} (the
   * engine already reported why via the StatusManager), exactly as the original appender did. A
   * second {@code start()} without an intervening {@code stop()} is a guarded no-op, so the engine
   * and its threads can never be orphaned.
   */
  @Override
  public void start() {
    if (isStarted()) {
      return;
    }
    NtfyConfig config = injectedConfig != null ? injectedConfig : buildConfigFromSetters();
    this.engine = new AlertEngine(config, new LogbackDiagnostics(this));
    engine.start();
    if (engine.isStarted()) {
      super.start();
    }
  }

  private NtfyConfig buildConfigFromSetters() {
    NtfyConfig.Builder builder =
        NtfyConfig.builder()
            .url(url)
            .topic(topic)
            .token(token)
            .username(username)
            .password(password)
            .title(title)
            .appName(appName)
            .excludedLoggers(excludedLoggers);
    if (maxStackFrames != null) {
      builder.maxStackFrames(maxStackFrames);
    }
    if (maxAlertsPerWindow != null) {
      builder.maxAlertsPerWindow(maxAlertsPerWindow);
    }
    if (connectTimeout != null) {
      builder.connectTimeout(DurationParser.parse(connectTimeout));
    }
    if (requestTimeout != null) {
      builder.requestTimeout(DurationParser.parse(requestTimeout));
    }
    if (suppressionWindow != null) {
      builder.suppressionWindow(DurationParser.parse(suppressionWindow));
    }
    if (errorPriority != null) {
      builder.errorPriority(errorPriority);
    }
    if (digestPriority != null) {
      builder.digestPriority(digestPriority);
    }
    if (errorTags != null) {
      builder.errorTags(errorTags);
    }
    if (digestTags != null) {
      builder.digestTags(digestTags);
    }
    if (clickUrl != null) {
      builder.clickUrl(clickUrl);
    }
    if (actions != null) {
      builder.actionsHeader(actions);
    }
    if (enabled != null) {
      builder.enabled(enabled);
    }
    return builder.build();
  }

  /**
   * Gates on ERROR, then maps the event and hands it to the engine, which owns all further gating
   * and publishing. The level gate lives here (mirroring the Quarkus adapter's SEVERE gate) so the
   * documented "ERROR-level events alert" contract holds on every install path — without it, a
   * root-logger auto-install would push every INFO/WARN line verbatim to the ntfy topic.
   */
  @Override
  protected void append(ILoggingEvent event) {
    AlertEngine e = engine;
    if (e == null) {
      return;
    }
    if (event.getLevel() == null || !event.getLevel().isGreaterOrEqual(Level.ERROR)) {
      return;
    }
    e.submit(LogbackEventMapper.map(event));
  }

  /**
   * Tears the engine down (flushing any pending digest) and marks this appender stopped. Null-safe
   * when never started and safe to call twice — {@code AlertEngine.stop()} is itself idempotent.
   */
  @Override
  public void stop() {
    if (engine != null) {
      engine.stop();
    }
    engine = null;
    super.stop();
  }
}
