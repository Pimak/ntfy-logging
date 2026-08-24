package io.github.pimak.ntfy.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, framework-neutral configuration for the ntfy engine and client. Built exclusively
 * through the fluent {@link Builder} ({@code NtfyConfig.builder()...build()}); every field is a
 * plain JDK type so adapters can populate it from Joran setters, Spring {@code @Configuration
 * Properties}, Quarkus {@code @ConfigMapping}, or {@link ConfigLoader} without core ever depending
 * on a framework.
 *
 * <p>Defaults are applied by the builder (not here) so a builder that is never touched still yields
 * a fully-populated config. Every collection-valued field is stored unmodifiable — stated as a
 * rule rather than a list of field names, which is what let this sentence go stale once already.
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
  private final String warnTopic;
  private final String warnPriority;
  private final String warnTags;
  private final boolean warnTopicFromClasspathFile;
  private final String clickUrl;
  private final String actions;
  private final List<String> excludedLoggerPrefixes;
  private final List<String> excludedExceptionTypes;
  private final boolean cache;
  private final boolean firebase;
  private final boolean deliveryPolicyValueRejected;
  private final String icon;
  private final Map<String, String> iconsByLoggerPrefix;
  private final boolean iconsByLoggerValueRejected;
  private final List<String> includeMdcKeys;
  private final boolean enabled;
  private final boolean asyncEnabled;
  private final int asyncQueueCapacity;
  private final boolean requireHttpsForCredentials;
  private final boolean endpointFromClasspathFile;
  private final boolean allowClasspathEndpoint;
  private final StartupPingMode startupPing;
  private final StartupPingMode startupPingWarn;
  private final boolean startupPingFailFast;
  private final boolean startupPingValueRejected;
  private final boolean startupPingWarnValueRejected;
  private final boolean startupPingNotifyFailures;
  private final Locale locale;

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
    this.warnTopic = b.warnTopic;
    this.warnPriority = b.warnPriority;
    this.warnTags = b.warnTags;
    this.warnTopicFromClasspathFile = b.warnTopicFromClasspathFile;
    this.clickUrl = b.clickUrl;
    this.actions = b.actions;
    this.excludedLoggerPrefixes =
        Collections.unmodifiableList(new ArrayList<>(b.excludedLoggerPrefixes));
    this.excludedExceptionTypes =
        Collections.unmodifiableList(new ArrayList<>(b.excludedExceptionTypes));
    this.cache = b.cache;
    this.firebase = b.firebase;
    this.deliveryPolicyValueRejected = b.cacheValueRejected || b.firebaseValueRejected;
    this.icon = b.icon;
    this.iconsByLoggerPrefix =
        Collections.unmodifiableMap(new LinkedHashMap<>(b.iconsByLoggerPrefix));
    this.iconsByLoggerValueRejected = b.iconsByLoggerValueRejected;
    this.includeMdcKeys = Collections.unmodifiableList(new ArrayList<>(b.includeMdcKeys));
    this.enabled = b.enabled;
    this.asyncEnabled = b.asyncEnabled;
    this.asyncQueueCapacity = b.asyncQueueCapacity;
    this.requireHttpsForCredentials = b.requireHttpsForCredentials;
    this.endpointFromClasspathFile = b.endpointFromClasspathFile;
    this.allowClasspathEndpoint = b.allowClasspathEndpoint;
    this.startupPing = b.startupPing;
    this.startupPingWarn = b.startupPingWarn;
    this.startupPingFailFast = b.startupPingFailFast;
    this.startupPingValueRejected = b.startupPingValueRejected;
    this.startupPingWarnValueRejected = b.startupPingWarnValueRejected;
    this.startupPingNotifyFailures = b.startupPingNotifyFailures;
    this.locale = b.locale;
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
   * The ntfy topic WARN-level events are published to, or {@code null}/blank when WARN alerting is
   * off (the default).
   *
   * <p>This single value is the whole opt-in switch — see {@link #isWarnRoutingEnabled()}. Set it
   * to the same value as {@link #getTopic()} to alert on warnings through the main topic at a
   * different priority.
   */
  public String getWarnTopic() {
    return warnTopic;
  }

  /**
   * The ntfy {@code Priority} header for WARN alerts (default {@code default}). Consulted only when
   * {@link #isWarnRoutingEnabled()} is true, and used for the WARN storm digest as well as
   * individual WARN alerts — the warn route is deliberately uniform, so routing warnings to a quiet
   * channel is not undone by its own digest arriving at {@code urgent}.
   */
  public String getWarnPriority() {
    return warnPriority;
  }

  /** The ntfy {@code Tags} header for WARN alerts and the WARN digest (default {@code warning}). */
  public String getWarnTags() {
    return warnTags;
  }

  /**
   * True when a non-blank {@link #getWarnTopic()} is configured, i.e. WARN-level events should be
   * alerted. False by default: alerting is ERROR-only unless an operator names a destination for
   * warnings.
   *
   * <p>Adapters gate on this before submitting a WARN event, and {@link AlertEngine} refuses WARN
   * events again on its own when it is false — the same double-floor discipline the Log4j2 adapter
   * already applies to its attach level.
   */
  public boolean isWarnRoutingEnabled() {
    return warnTopic != null && !warnTopic.isBlank();
  }

  /**
   * True when {@link #getWarnTopic()} was supplied ONLY by a classpath {@code ntfy.properties} —
   * no {@code ntfy.warn-topic} system property and no {@code NTFY_WARN_TOPIC} environment variable.
   * Set by {@link ConfigLoader} only.
   *
   * <p>Mirrors {@link #isEndpointFromClasspathFile()} and exists for the same reason: {@code
   * warn-topic} names a destination and widens the volume of log content leaving the host, so on
   * the zero-code auto-install paths it is subject to the same {@code allow-classpath-endpoint}
   * opt-in. (Contrast {@code include-mdc-keys}, which is read from all three layers precisely
   * because it cannot redirect where alerts go.)
   *
   * <p>The refusal itself lives in {@link AlertEngine#start()} rather than in the three per-adapter
   * ambient guards that handle the endpoint case. Those guards must refuse INSTALLATION outright,
   * which only they can do; dropping an optional extra route is a decision the engine can make once
   * for every adapter. Configs built explicitly — a {@code <Ntfy warnTopic="…">} attribute, a
   * {@code <warnTopic>} element, {@code application.yml} — never set this flag and are never
   * affected.
   */
  public boolean isWarnTopicFromClasspathFile() {
    return warnTopicFromClasspathFile;
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

  /**
   * The unmodifiable list of fully qualified exception class names whose presence ANYWHERE in an
   * event's cause chain gates the event out. Empty by default.
   *
   * <p>Matched as whole names, not prefixes — the opposite of {@link #getExcludedLoggerPrefixes()},
   * and deliberately so. A logger name is hierarchical, so a prefix names a subtree an operator can
   * reason about; a class name is not, so a prefix there would silently catch unrelated types that
   * merely share a spelling.
   */
  public List<String> getExcludedExceptionTypes() {
    return excludedExceptionTypes;
  }

  /**
   * Whether the ntfy server may cache published messages ({@code true} by default, matching ntfy's
   * own default of storing them for 12 hours).
   *
   * <p>{@code false} sends {@code Cache: no}. The message then reaches CONNECTED subscribers only:
   * a subscriber with a momentary network problem never receives it, and {@code since=}/{@code
   * poll=1} stop returning it. That is a real trade — privacy against delivery to anyone who was
   * not listening at the time.
   */
  public boolean isCache() {
    return cache;
  }

  /**
   * Whether the ntfy server may forward published messages to Firebase Cloud Messaging ({@code
   * true} by default).
   *
   * <p>Worth knowing before leaving it on: ntfy.sh is configured to use FCM, so on the default
   * endpoint every alert — stack traces and allow-listed MDC values included — also transits
   * Google's infrastructure. {@code false} sends {@code Firebase: no}. The cost is delivery
   * latency on the Google Play Android client when instant delivery is off (up to 15 minutes); on
   * a self-hosted server with no FCM configured the header is simply inert.
   */
  public boolean isFirebase() {
    return firebase;
  }

  /**
   * True when a {@code cache} or {@code firebase} value was supplied but could not be read as a
   * boolean, so the default was kept. Exists for the same reason as {@link
   * #isStartupPingValueRejected()}: on these two keys a silent misparse costs delivered alerts,
   * so an unreadable value has to be reported rather than guessed at.
   */
  public boolean isDeliveryPolicyValueRejected() {
    return deliveryPolicyValueRejected;
  }

  /**
   * URL of the icon shown beside the notification (ntfy {@code Icon} header), or {@code null} when
   * unset. Checked at startup for being a fetchable http(s) URL, because a bad one is
   * otherwise undetectable — the subscriber's client fetches the icon, not the server, so
   * nothing on the publish path ever reports it. The FORMAT is not checked: ntfy renders
   * JPEG and PNG only, but a URL says little about the bytes behind it.
   */
  public String getIcon() {
    return icon;
  }

  /**
   * The unmodifiable logger-prefix &rarr; icon-URL table that overrides {@link #getIcon()} for
   * alerts, in configured order. Empty by default.
   *
   * <p>A table rather than placeholders in a single URL, and the difference is a security one. The
   * subscriber's client downloads the icon automatically when it DISPLAYS a notification — no tap
   * involved — so an interpolated URL would send whatever it interpolated, plus the subscriber's
   * IP, to a third-party host on every notification. A table only ever selects among URLs the
   * operator wrote out in full, so nothing derived from a running application ever leaves.
   *
   * <p>Applies to alerts only. A notification published through {@link NtfyClient} has no logger
   * to match on and always carries {@link #getIcon()}.
   */
  public Map<String, String> getIconsByLoggerPrefix() {
    return iconsByLoggerPrefix;
  }

  /**
   * True when an {@code icons-by-logger} entry could not be read as a {@code prefix=url}
   * pair, so it was discarded. Exists for the same reason as {@link
   * #isStartupPingValueRejected()}: without it a pair mistyped with a space instead of an
   * {@code =} would be indistinguishable from an unset key, and would produce no icon and no
   * signal — the invisible failure the icon URL check exists to prevent, reintroduced one
   * layer earlier.
   */
  public boolean isIconsByLoggerValueRejected() {
    return iconsByLoggerValueRejected;
  }

  /**
   * The unmodifiable, EXPLICIT allow-list of MDC keys whose values are rendered into alert bodies
   * (one {@code key: value} line each), in configured order — that order is the rendering order.
   * Empty by default, i.e. no MDC content is published at all unless an operator opts in.
   *
   * <p>There is deliberately NO wildcard and no "include everything" mode: a production MDC routinely
   * carries session tokens and user identifiers, so no MDC value may ever leave the process in a
   * notification unless it was named here. Adapters project the live context through {@link
   * MdcProjector#project}, which also scrubs, truncates, and caps whatever the named keys hold.
   */
  public List<String> getIncludeMdcKeys() {
    return includeMdcKeys;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * True when delivery of individual error alerts should be offloaded to the engine's bounded
   * asynchronous work queue instead of running inline on the calling (logging) thread. On by
   * default (since 2.0): a slow or unreachable ntfy server can never back-pressure application
   * threads during an error storm — see {@link AlertEngine} for the queue/overflow semantics.
   * Disable it to restore the pre-2.0 synchronous behavior, where each publish blocks the calling
   * thread until the HTTP exchange completes — useful for short-lived processes (batch jobs, CLIs)
   * that must know the alert left the process before the logging call returns.
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
   * sent over a cleartext {@code http://} endpoint. On by default (since 2.0): secrets never
   * traverse the network unencrypted unless the operator explicitly opts out. Setting it to {@code
   * false} restores the pre-2.0 warn-and-activate behavior for deliberate self-hosted plain-HTTP
   * setups.
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
   * True when the operator has explicitly opted in to activating alert delivery from an endpoint
   * URL supplied only by a classpath {@code ntfy.properties} (see {@link
   * #isEndpointFromClasspathFile()}). Default {@code false}: because any jar on the classpath can
   * ship such a file and silently redirect log-derived alerts (messages and stack traces) to a
   * server the operator never chose, auto-installing adapters refuse classpath-only activation
   * unless this flag is set (kebab key {@code allow-classpath-endpoint}, env {@code
   * NTFY_ALLOW_CLASSPATH_ENDPOINT}, sysprop {@code ntfy.allow-classpath-endpoint}).
   */
  public boolean isAllowClasspathEndpoint() {
    return allowClasspathEndpoint;
  }

  /**
   * What the opt-in startup self-test does once the engine has finished activating — see {@link
   * StartupPingMode}. Defaults to {@link StartupPingMode#OFF}, which is a true no-op: no request is
   * made and no diagnostic line is emitted, so the default path's diagnostic stream is unchanged.
   * Never {@code null}.
   */
  public StartupPingMode getStartupPing() {
    return startupPing;
  }

  /**
   * True when a failed startup self-test must abort application startup (by throwing {@link
   * NtfyStartupSelfTestException} out of {@code AlertEngine.start()}) rather than merely warn.
   * Default {@code false}: a logging backend must never be able to kill the application it
   * instruments, so failing the deployment is strictly opt-in.
   *
   * <p>Enabling this also makes the self-test run <em>inline</em> instead of on a background
   * thread, because a start that has already returned can no longer be aborted. That is a bounded
   * but real addition to startup time (at most {@code connect-timeout + request-timeout}), and is
   * the reason this is aimed at CI/staging rather than production.
   *
   * <p>Inert when {@link #getStartupPing()} is {@link StartupPingMode#OFF}; the engine warns about
   * that combination, since it is always a mistake.
   */
  public boolean isStartupPingFailFast() {
    return startupPingFailFast;
  }

  /**
   * True when a {@code startup-ping} value was supplied but matched no known mode, so the default
   * was kept. Exists purely so the engine can warn about the typo at start: without it, a
   * misspelled {@code startup-ping=prob} would be indistinguishable from an unset key and would
   * silently behave as {@code off} — the exact class of silent misconfiguration this whole feature
   * was added to eliminate.
   */
  public boolean isStartupPingValueRejected() {
    return startupPingValueRejected;
  }

  /**
   * What the startup self-test does for the optional WARN route — see {@link StartupPingMode}.
   * Independent of {@link #getStartupPing()} and likewise {@link StartupPingMode#OFF} by default,
   * because the two routes are configured, permissioned and paid for separately: ntfy grants access
   * per topic, so a healthy ERROR route is no evidence at all about the WARN one, and in {@code
   * publish} mode each route costs its own notification on every boot. Never {@code null}.
   *
   * <p>Only consulted when the WARN route actually survived its own preconditions; testing a route
   * the engine has withdrawn would report on something that is not going to be used.
   */
  public StartupPingMode getStartupPingWarn() {
    return startupPingWarn;
  }

  /** As {@link #isStartupPingValueRejected()}, for {@code startup-ping-warn}. */
  public boolean isStartupPingWarnValueRejected() {
    return startupPingWarnValueRejected;
  }

  /**
   * Whether a failed self-test should be announced as an ntfy notification on a route that PASSED.
   * On by default — the one opt-OUT in this feature.
   *
   * <p>A diagnostic line lands on a status manager or stderr that nobody reads until they are
   * already investigating; the point of alerting is that it reaches people. When one route is
   * broken and another is proven healthy, the healthy one can carry that news to where operators
   * actually look.
   *
   * <p>It cannot fire unless some route's self-test PASSED, which is what stops it from publishing
   * to a topic the self-test has not just verified: with no WARN route tested, nothing fails, and
   * the ERROR topic is never pinged. Note the one surprise it does introduce — with this on, a
   * self-test configured purely as {@code probe} can still publish exactly one notification, the
   * failure report. Set it {@code false} to keep {@code probe} absolutely publish-free.
   */
  public boolean isStartupPingNotifyFailures() {
    return startupPingNotifyFailures;
  }

  /**
   * Language of the notification bodies and self-diagnostic messages the engine produces. Defaults
   * to {@link Locale#ENGLISH} and, deliberately, never follows the host JVM's default locale, so the
   * language of alerts is deterministic regardless of where the process runs. A locale with no
   * shipped translation falls back wholesale to English; a partial translation degrades to English
   * per missing key. Never {@code null}.
   */
  public Locale getLocale() {
    return locale;
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
    private String warnTopic;
    // Quieter than the error route on purpose: `default` is ntfy's no-op priority (no sound or
    // vibration escalation) and `warning` its ⚠️ tag, so a warning never looks or feels like a page.
    private String warnPriority = "default";
    private String warnTags = "warning";
    private boolean warnTopicFromClasspathFile = false;
    private String clickUrl;
    private String actions;
    private List<String> excludedLoggerPrefixes = new ArrayList<>();
    private List<String> excludedExceptionTypes = new ArrayList<>();
    private boolean cache = true;
    private boolean firebase = true;
    private boolean cacheValueRejected = false;
    private boolean firebaseValueRejected = false;
    private String icon;
    private Map<String, String> iconsByLoggerPrefix = new LinkedHashMap<>();
    private boolean iconsByLoggerValueRejected = false;
    private List<String> includeMdcKeys = new ArrayList<>();
    private boolean enabled = true;
    private boolean asyncEnabled = true;
    private int asyncQueueCapacity = 1024;
    private boolean requireHttpsForCredentials = true;
    private boolean endpointFromClasspathFile = false;
    private boolean allowClasspathEndpoint = false;
    private StartupPingMode startupPing = StartupPingMode.OFF;
    private StartupPingMode startupPingWarn = StartupPingMode.OFF;
    private boolean startupPingFailFast = false;
    private boolean startupPingValueRejected = false;
    private boolean startupPingWarnValueRejected = false;
    // Opt-OUT: when a self-test fails and another route is proven healthy, saying so on that
    // route is the whole difference between a log line nobody greps and news that reaches an
    // operator. It can never fire without a passing route, so it cannot ping a topic the
    // self-test has not just verified.
    private boolean startupPingNotifyFailures = true;
    // Default English, NOT Locale.getDefault(): alert language must be deterministic and never
    // silently inherit whatever locale the host JVM happens to run under.
    private Locale locale = Locale.ENGLISH;

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

    /**
     * The topic WARN-level events are published to. Setting it to a non-blank value is the entire
     * opt-in for WARN alerting — see {@link NtfyConfig#isWarnRoutingEnabled()}. {@code null}/blank
     * (the default) keeps alerting ERROR-only.
     */
    public Builder warnTopic(String warnTopic) {
      this.warnTopic = warnTopic;
      return this;
    }

    /** ntfy {@code Priority} header for WARN alerts and the WARN digest; default {@code default}. */
    public Builder warnPriority(String warnPriority) {
      this.warnPriority = warnPriority;
      return this;
    }

    /** ntfy {@code Tags} header for WARN alerts and the WARN digest; default {@code warning}. */
    public Builder warnTags(String warnTags) {
      this.warnTags = warnTags;
      return this;
    }

    /** See {@link NtfyConfig#isWarnTopicFromClasspathFile()}; set by {@link ConfigLoader} only. */
    public Builder warnTopicFromClasspathFile(boolean warnTopicFromClasspathFile) {
      this.warnTopicFromClasspathFile = warnTopicFromClasspathFile;
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

    /**
     * Sets the excluded exception types from an explicit list of fully qualified class names
     * (defensively copied, null-safe, blanks dropped).
     */
    public Builder excludedExceptionTypes(List<String> types) {
      this.excludedExceptionTypes = normalizeCsvEntries(types);
      return this;
    }

    /**
     * Convenience: sets the excluded exception types from a single comma-separated string of fully
     * qualified class names, trimming each entry and dropping blanks. A {@code null}/blank csv
     * clears the list. This is the spelling every adapter's flat configuration surface uses.
     */
    public Builder excludedExceptionTypesCsv(String csv) {
      List<String> types = new ArrayList<>();
      if (csv != null && !csv.isBlank()) {
        for (String part : csv.split(",")) {
          types.add(part);
        }
      }
      this.excludedExceptionTypes = normalizeCsvEntries(types);
      return this;
    }

    /**
     * {@code false} asks the server not to store the message ({@code Cache: no}), at the cost of
     * every subscriber who is not connected at that moment. Default {@code true}.
     */
    public Builder cache(boolean cache) {
      this.cache = cache;
      // A typed value is unambiguous, so it also settles any earlier rejection of this key.
      this.cacheValueRejected = false;
      return this;
    }

    /**
     * Sets {@code cache} from a raw configuration string, the spelling every flat surface hands
     * over — an XML attribute, an environment variable, a properties entry.
     *
     * <p>Exists so the answer to "what does {@code cache=yes} mean" cannot differ between
     * surfaces. Each adapter converting on its own gets {@code Boolean.valueOf}, which maps
     * everything that is not {@code "true"} to false — so {@code yes} would turn caching OFF and
     * cost every offline subscriber their alerts, silently, on exactly the surfaces where the
     * value is typed by hand. A {@code null} leaves the default; an unreadable value leaves the
     * default and is recorded for the engine to warn about at {@code start()}.
     */
    public Builder cache(String cache) {
      Boolean parsed = parseFlag(cache);
      if (parsed != null) {
        this.cache = parsed;
      }
      // Per key, not a single latch: setting cache twice — the second time readably — must not
      // leave the engine warning about a value that no longer exists.
      this.cacheValueRejected = parsed == null && cache != null && !cache.isBlank();
      return this;
    }

    /** {@code firebase} from a raw configuration string. See {@link #cache(String)}. */
    public Builder firebase(String firebase) {
      Boolean parsed = parseFlag(firebase);
      if (parsed != null) {
        this.firebase = parsed;
      }
      this.firebaseValueRejected = parsed == null && firebase != null && !firebase.isBlank();
      return this;
    }

    /**
     * {@code TRUE}/{@code FALSE} for a value this project is willing to call a boolean, {@code
     * null} for anything else — including {@code null} itself, so "absent" and "unreadable" are
     * told apart by the caller rather than here.
     */
    private static Boolean parseFlag(String raw) {
      if (raw == null) {
        return null;
      }
      String v = raw.trim().toLowerCase(Locale.ROOT);
      if (v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1")) {
        return Boolean.TRUE;
      }
      if (v.equals("false") || v.equals("no") || v.equals("off") || v.equals("0")) {
        return Boolean.FALSE;
      }
      return null;
    }

    /**
     * {@code false} asks the server not to forward the message to Firebase ({@code Firebase: no}),
     * at the cost of push latency on the Google Play Android client. Default {@code true}.
     *
     * <p>Independent of {@link #cache(boolean)} on purpose: a self-hosted server with no FCM
     * configured wants this off and caching on, and one combined switch would make that
     * unreachable.
     */
    public Builder firebase(boolean firebase) {
      this.firebase = firebase;
      // A typed value is unambiguous, so it also settles any earlier rejection of this key.
      this.firebaseValueRejected = false;
      return this;
    }

    /**
     * Sets the notification icon URL (ntfy {@code Icon} header). Must be an {@code http}/{@code
     * https} URL; a value that is not one is dropped with a diagnostic at {@code start()} rather
     * than sent. ntfy renders JPEG and PNG only, but the format is a property of the bytes
     * served rather than of the path, so an extension naming another format only earns a
     * warning.
     */
    public Builder icon(String icon) {
      this.icon = icon;
      return this;
    }

    /**
     * Sets the logger-prefix &rarr; icon-URL table (defensively copied, null-safe). Entries with a
     * blank prefix or URL are dropped; an entry whose URL ntfy could not render is dropped with a
     * diagnostic at {@code start()}, leaving the rest of the table working.
     */
    public Builder iconsByLoggerPrefix(Map<String, String> icons) {
      this.iconsByLoggerPrefix = normalizeIconTable(icons);
      this.iconsByLoggerValueRejected = false;
      return this;
    }

    /**
     * Convenience: the same table as a single string, {@code prefix=url} pairs separated by commas
     * — the spelling every adapter's flat configuration surface uses. Only the FIRST {@code =} of
     * a pair separates prefix from URL, so a query string may contain more.
     */
    public Builder iconsByLoggerPrefixCsv(String csv) {
      Map<String, String> icons = new LinkedHashMap<>();
      boolean rejected = false;
      if (csv != null && !csv.isBlank()) {
        for (String pair : csv.split(",")) {
          int split = pair.indexOf('=');
          if (split > 0) {
            icons.put(pair.substring(0, split), pair.substring(split + 1));
          } else if (!pair.isBlank()) {
            // Recorded rather than skipped: the engine warns about it at start(). Dropping a
            // pair quietly would leave one mistyped with a space looking exactly like an
            // unset key.
            rejected = true;
          }
        }
      }
      this.iconsByLoggerPrefix = normalizeIconTable(icons);
      this.iconsByLoggerValueRejected = rejected;
      return this;
    }

    /** Trims both sides and drops any pair with a blank half, so the table never holds a no-op. */
    private static Map<String, String> normalizeIconTable(Map<String, String> raw) {
      Map<String, String> normalized = new LinkedHashMap<>();
      if (raw != null) {
        for (Map.Entry<String, String> entry : raw.entrySet()) {
          String prefix = entry.getKey();
          String url = entry.getValue();
          if (prefix != null && !prefix.isBlank() && url != null && !url.isBlank()) {
            normalized.put(prefix.trim(), url.trim());
          }
        }
      }
      return normalized;
    }

    /** Trims, drops nulls and blanks. Shared so the list and csv spellings cannot disagree. */
    private static List<String> normalizeCsvEntries(List<String> raw) {
      List<String> normalized = new ArrayList<>();
      if (raw != null) {
        for (String entry : raw) {
          if (entry != null && !entry.isBlank()) {
            normalized.add(entry.trim());
          }
        }
      }
      return normalized;
    }

    /**
     * Sets the MDC-key allow-list from an explicit list (defensively copied, null-safe); a {@code
     * null} list clears it. Order is preserved and is the order the entries are rendered in. See
     * {@link NtfyConfig#getIncludeMdcKeys()} — there is no wildcard form on purpose.
     */
    public Builder includeMdcKeys(List<String> keys) {
      // Trimmed, with null/blank entries dropped — the same normalization includeMdcKeysCsv
      // performs, so the two spellings of one setting cannot disagree about what the allow-list
      // contains. MdcProjector skips such entries anyway; normalizing here means the stored config
      // never advertises a key that will never be rendered, which is what the startup diagnostic
      // reports from.
      List<String> normalized = new ArrayList<>();
      if (keys != null) {
        for (String key : keys) {
          if (key != null && !key.isBlank()) {
            normalized.add(key.trim());
          }
        }
      }
      this.includeMdcKeys = normalized;
      return this;
    }

    /**
     * Convenience: sets the MDC-key allow-list from a single comma-separated string, trimming each
     * entry and dropping blanks. A {@code null}/blank csv clears the list. This is the flat-string
     * entry point used by environment/properties configuration.
     *
     * <p>Deliberately a distinct method rather than a {@code String}/{@code List} overload pair
     * (matching the existing {@link #excludedLoggerPrefixes}/{@link #excludedLoggers} precedent): an
     * overload would make a bare {@code null} literal ambiguous at the call site.
     *
     * <p>Limitation: an MDC key containing a comma is unreachable from any CSV surface — use {@link
     * #includeMdcKeys(List)} for that.
     */
    public Builder includeMdcKeysCsv(String csv) {
      List<String> keys = new ArrayList<>();
      if (csv != null && !csv.isBlank()) {
        for (String part : csv.split(",")) {
          String trimmed = part.trim();
          if (!trimmed.isEmpty()) {
            keys.add(trimmed);
          }
        }
      }
      this.includeMdcKeys = keys;
      return this;
    }

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Toggles asynchronous (offloaded) delivery of individual error alerts. On by default; set
     * {@code false} for pre-2.0 synchronous delivery. See {@link NtfyConfig#isAsyncEnabled()}.
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
     * Toggles refusing activation when credentials would traverse a cleartext {@code http://}
     * endpoint. On by default; set {@code false} for the pre-2.0 warn-and-activate behavior. See
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

    /**
     * Explicit opt-in for activating from a classpath-only endpoint URL; see {@link
     * NtfyConfig#isAllowClasspathEndpoint()}. Off by default.
     */
    public Builder allowClasspathEndpoint(boolean allowClasspathEndpoint) {
      this.allowClasspathEndpoint = allowClasspathEndpoint;
      return this;
    }

    /**
     * Selects the opt-in startup self-test mode (see {@link NtfyConfig#getStartupPing()}). A {@code
     * null} argument resets to the {@link StartupPingMode#OFF} default.
     */
    public Builder startupPing(StartupPingMode startupPing) {
      this.startupPing = startupPing == null ? StartupPingMode.OFF : startupPing;
      this.startupPingValueRejected = false;
      return this;
    }

    /**
     * Convenience overload parsing a configured string ({@code off}/{@code probe}/{@code publish},
     * case-insensitive) so the XML and properties surfaces bind without carrying their own parser.
     *
     * <p>A {@code null}/blank tag keeps the current value, and an UNRECOGNISED value keeps it too
     * rather than throwing — the same lenient contract as {@link #locale(String)} and the numeric
     * setters, so one typo in a config file can never take down the host application. The engine
     * warns about the typo at start (it can tell "unset" from "unparseable" because {@link
     * StartupPingMode#fromValue} returns {@code null} only for the latter), and the caller may
     * pre-check with {@code fromValue} when it wants to warn earlier.
     */
    public Builder startupPing(String startupPing) {
      if (startupPing == null || startupPing.isBlank()) {
        return this;
      }
      StartupPingMode parsed = StartupPingMode.fromValue(startupPing);
      if (parsed == null) {
        // Keep the current value, but remember that a value WAS supplied and rejected, so the
        // engine can warn instead of letting the typo pass for a deliberate "off".
        this.startupPingValueRejected = true;
        return this;
      }
      this.startupPing = parsed;
      this.startupPingValueRejected = false;
      return this;
    }

    /**
     * Toggles aborting application startup when the self-test fails; see {@link
     * NtfyConfig#isStartupPingFailFast()}. Off by default, and also switches the self-test from
     * background to inline execution when on.
     */
    public Builder startupPingFailFast(boolean startupPingFailFast) {
      this.startupPingFailFast = startupPingFailFast;
      return this;
    }

    /**
     * Selects the startup self-test mode for the WARN route (see {@link
     * NtfyConfig#getStartupPingWarn()}). A {@code null} argument resets to {@link
     * StartupPingMode#OFF}.
     */
    public Builder startupPingWarn(StartupPingMode startupPingWarn) {
      this.startupPingWarn = startupPingWarn == null ? StartupPingMode.OFF : startupPingWarn;
      this.startupPingWarnValueRejected = false;
      return this;
    }

    /** Lenient string overload for {@code startup-ping-warn}; see {@link #startupPing(String)}. */
    public Builder startupPingWarn(String startupPingWarn) {
      if (startupPingWarn == null || startupPingWarn.isBlank()) {
        return this;
      }
      StartupPingMode parsed = StartupPingMode.fromValue(startupPingWarn);
      if (parsed == null) {
        this.startupPingWarnValueRejected = true;
        return this;
      }
      this.startupPingWarn = parsed;
      this.startupPingWarnValueRejected = false;
      return this;
    }

    /**
     * Toggles announcing a failed self-test on a route that passed; see {@link
     * NtfyConfig#isStartupPingNotifyFailures()}. On by default.
     */
    public Builder startupPingNotifyFailures(boolean startupPingNotifyFailures) {
      this.startupPingNotifyFailures = startupPingNotifyFailures;
      return this;
    }

    /**
     * Sets the language of notification bodies and diagnostics (see {@link NtfyConfig#getLocale()}).
     * A {@code null} argument resets to the {@link Locale#ENGLISH} default.
     */
    public Builder locale(Locale locale) {
      this.locale = locale == null ? Locale.ENGLISH : locale;
      return this;
    }

    /**
     * Convenience overload that parses a BCP 47 language tag (e.g. {@code "fr"}, {@code "de-DE"})
     * via {@link Locale#forLanguageTag(String)}. A {@code null}/blank tag, or one whose language
     * subtag is undetermined (garbage input, bare {@code "und"}, {@code "und-Latn"}, private-use
     * {@code "x-..."} — all of which parse to an empty {@link Locale#getLanguage()}), keeps the
     * {@link Locale#ENGLISH} default rather than silently selecting the undetermined locale.
     */
    public Builder locale(String languageTag) {
      if (languageTag == null || languageTag.isBlank()) {
        return this;
      }
      Locale parsed = Locale.forLanguageTag(languageTag.trim());
      if (parsed.getLanguage().isEmpty()) {
        return this;
      }
      this.locale = parsed;
      return this;
    }

    public NtfyConfig build() {
      return new NtfyConfig(this);
    }
  }
}
