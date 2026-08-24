package io.github.pimak.ntfy.logback;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.AlertLevel;
import io.github.pimak.ntfy.core.DurationParser;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PipelineCounters;

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
  private String excludedExceptionTypes;
  private String cache;
  private String firebase;
  private String icon;
  private String iconsByLogger;
  private String includeMdcKeys;
  private String connectTimeout;
  private String requestTimeout;
  private String suppressionWindow;
  private String errorPriority;
  private String digestPriority;
  private String errorTags;
  private String digestTags;
  private String warnTopic;
  private String warnPriority;
  private String warnTags;
  private String clickUrl;
  private String actions;
  private String locale;
  private Integer maxStackFrames;
  private Integer maxAlertsPerWindow;
  private Boolean enabled;
  private Boolean async;
  private Integer asyncQueueCapacity;
  private Boolean requireHttpsForCredentials;
  private String startupPing;
  private String startupPingWarn;
  private Boolean startupPingFailFast;
  private Boolean startupPingNotifyFailures;

  // Pre-built config injected by the Configurator; when present it wins over the setters.
  private NtfyConfig injectedConfig;

  private AlertEngine engine;

  /**
   * The RESOLVED {@code include-mdc-keys} allow-list handed to the mapper on every append. Empty
   * until {@code start()} resolves it, and empty again after {@code stop()}, so an appender that
   * never activated can never project an MDC value into a body.
   *
   * <p>{@code volatile} for the same reason as {@link #engine}: {@code start()}/{@code stop()} run
   * on a configuration thread (Joran, or the auto-installer) while {@link #append} runs on
   * application threads, and the assignment must be visible to them without a lock on the logging
   * path.
   */
  private volatile List<String> mdcKeys = List.of();

  /**
   * Whether the started engine actually activated a WARN route, i.e. whether {@link #append} should
   * let WARN events through. False until {@code start()} resolves it and false again after {@code
   * stop()}, so an appender that never activated — or one whose warn topic the engine rejected —
   * can never publish a warning. {@code volatile} for the same reason as {@link #mdcKeys}.
   */
  private volatile boolean warnRoutingActive = false;

  // Read-only pipeline observability counters. Owned by the appender (which outlives individual
  // engines) and handed to every engine built in start(), so the tallies survive stop()/start()
  // cycles and the reset/reattach handled by NtfyLogbackReattachListener — i.e. they are monotonic
  // for this appender instance's whole lifetime.
  private final PipelineCounters counters = new PipelineCounters();

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

  /**
   * The topic WARN-level events are published to. Setting it to a non-blank value is the entire
   * opt-in for WARN alerting: unset (the default), this appender alerts on ERROR and above only.
   * Set it to the same value as {@code <topic>} to alert on warnings through the main topic at a
   * different priority. XML: {@code <warnTopic>my-app-warnings</warnTopic>}.
   */
  public void setWarnTopic(String warnTopic) {
    this.warnTopic = warnTopic;
  }

  /** ntfy {@code Priority} header for WARN alerts and the WARN digest; default {@code default}. */
  public void setWarnPriority(String warnPriority) {
    this.warnPriority = warnPriority;
  }

  /** ntfy {@code Tags} header for WARN alerts and the WARN digest; default {@code warning}. */
  public void setWarnTags(String warnTags) {
    this.warnTags = warnTags;
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

  /**
   * Comma-separated fully qualified exception class names. An event whose cause chain contains any
   * of them never alerts — matched anywhere in the chain, not just on the surface throwable.
   */
  public void setExcludedExceptionTypes(String excludedExceptionTypes) {
    this.excludedExceptionTypes = excludedExceptionTypes;
  }

  /**
   * {@code false} asks the server not to store the message ({@code Cache: no}). Subscribers who are
   * offline at that moment never receive it. Default {@code true}.
   *
   * <p>Declared as a String rather than a boolean on purpose. Joran converts an XML value with
   * {@code Boolean.valueOf}, which maps everything that is not {@code "true"} to false — so {@code
   * <cache>yes</cache>} would turn caching OFF and cost every offline subscriber their alerts,
   * silently, on the one surface where the value is typed by hand. {@code NtfyConfig.Builder} reads
   * the common spellings and reports anything else at {@code start()}.
   */
  public void setCache(String cache) {
    this.cache = cache;
  }

  /**
   * {@code false} asks the server not to forward the message to Firebase ({@code Firebase: no}).
   * Default {@code true}. A String for the same reason as {@link #setCache(String)}.
   */
  public void setFirebase(String firebase) {
    this.firebase = firebase;
  }

  /**
   * URL of the icon shown beside the notification (ntfy {@code Icon} header). Must be an {@code http}/{@code https} URL; a value that is not one is dropped with a diagnostic. ntfy renders JPEG and PNG only, but that is a property of the bytes served, not of the path, so a URL whose extension names another format is warned about and still sent.
   */
  public void setIcon(String icon) {
    this.icon = icon;
  }

  /**
   * Per-logger icon overrides as {@code prefix=url} pairs separated by commas. The
   * longest prefix matching an alert's logger wins; anything unmatched keeps the default icon.
   * Alerts only — a notification has no logger to match on.
   */
  public void setIconsByLogger(String iconsByLogger) {
    this.iconsByLogger = iconsByLogger;
  }

  /**
   * A comma-separated allow-list of MDC keys rendered into alert bodies, one {@code key: value} line
   * each, in the order given here. Unset by default, and there is deliberately no wildcard form: an
   * MDC value reaches a notification only when an operator named its key, because a production MDC
   * routinely holds tokens and user identifiers that must never leave the process.
   */
  public void setIncludeMdcKeys(String includeMdcKeys) {
    this.includeMdcKeys = includeMdcKeys;
  }

  /**
   * Language of notification bodies and diagnostics as a BCP 47 tag (e.g. {@code fr}, {@code de-DE}).
   * Defaults to English; an unknown/unshipped locale silently uses English. XML: {@code
   * <locale>fr</locale>}.
   */
  public void setLocale(String locale) {
    this.locale = locale;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Asynchronous (offloaded) delivery: individual error alerts are handed to the engine's bounded
   * work queue and published by a daemon worker, so a slow/unreachable ntfy server never blocks
   * the logging thread. On by default; set {@code false} for pre-2.0 synchronous delivery.
   */
  public void setAsync(boolean async) {
    this.async = async;
  }

  /** Bounded async delivery queue capacity; only consulted when {@code async} is {@code true}. */
  public void setAsyncQueueCapacity(int asyncQueueCapacity) {
    this.asyncQueueCapacity = asyncQueueCapacity;
  }

  /**
   * Strict mode: refuse engine activation (rather than only warn) when credentials would be sent
   * over a cleartext {@code http://} endpoint. On by default; set {@code false} for the pre-2.0
   * warn-and-activate behavior.
   */
  public void setRequireHttpsForCredentials(boolean requireHttpsForCredentials) {
    this.requireHttpsForCredentials = requireHttpsForCredentials;
  }

  /**
   * Opt-in startup self-test: {@code off} (default), {@code probe} (read-only reachability check,
   * publishes nothing) or {@code publish} (a real low-priority test notification). Verifies at boot
   * that the configured endpoint, credentials and topic actually work, instead of finding out when
   * the first real error alert fails to deliver. An unrecognized value keeps the default and is
   * warned about on the Logback status manager.
   */
  public void setStartupPing(String startupPing) {
    this.startupPing = startupPing;
  }

  /**
   * When {@code true}, a failed startup self-test aborts application startup instead of only
   * warning. Off by default, and it also makes the self-test run inline (adding at most
   * connect+request timeout to boot) since a start that already returned cannot be aborted. Aimed
   * at CI/staging rather than production.
   */
  public void setStartupPingFailFast(boolean startupPingFailFast) {
    this.startupPingFailFast = startupPingFailFast;
  }

  /**
   * Startup self-test for the optional WARN route: {@code off} (default), {@code probe} or
   * {@code publish}. Independent of {@link #setStartupPing(String)} — ntfy permissions are granted
   * per topic, so a healthy ERROR route proves nothing about the WARN one. Only consulted when a
   * {@code warnTopic} is configured and its route survived.
   */
  public void setStartupPingWarn(String startupPingWarn) {
    this.startupPingWarn = startupPingWarn;
  }

  /**
   * Whether a failed self-test is announced as a notification on a route that PASSED. On by
   * default; it can never fire unless some route passed, so it cannot publish to a topic the
   * self-test has not just verified. Set {@code false} to keep {@code probe} absolutely
   * publish-free.
   */
  public void setStartupPingNotifyFailures(boolean startupPingNotifyFailures) {
    this.startupPingNotifyFailures = startupPingNotifyFailures;
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
   * The resolved {@code include-mdc-keys} allow-list this appender is currently projecting with —
   * empty before {@code start()} and after {@code stop()}. Package-private: it exists so the
   * setter&rarr;config&rarr;appender binding can be asserted without standing up an HTTP endpoint,
   * and is not part of the public appender surface.
   */
  List<String> resolvedMdcKeys() {
    return mdcKeys;
  }

  /**
   * Whether this appender is currently letting WARN events through — the engine's post-{@code
   * start()} decision, not the configured request. False before {@code start()} and after {@code
   * stop()}. Package-private for the same reason as {@link #resolvedMdcKeys()}: it lets the
   * setter&rarr;config&rarr;engine binding be asserted without standing up an HTTP endpoint.
   */
  boolean warnRoutingActive() {
    return warnRoutingActive;
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
    // Taken from the BUILT config, never from the raw includeMdcKeys setter string: that is what
    // makes the setConfig(...) injected-config path work too (the auto-install path used by
    // NtfyLogbackConfigurator, whose keys come from the environment via ConfigLoader and never
    // touch a JavaBean setter). It also means the CSV parsing/normalization lives in exactly one
    // place — NtfyConfig.Builder#includeMdcKeysCsv.
    this.mdcKeys = config.getIncludeMdcKeys();
    this.engine = new AlertEngine(config, new LogbackDiagnostics(this), counters);
    engine.start();
    // The ENGINE's decision, not the config's request: start() withdraws the warn route for an
    // invalid warn topic or a classpath-only one, and gating on the request would leave this
    // appender submitting warnings the engine just drops.
    this.warnRoutingActive = engine.isWarnRoutingActive();
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
            .excludedLoggers(excludedLoggers)
            .excludedExceptionTypesCsv(excludedExceptionTypes)
            .cache(cache)
            .firebase(firebase)
            .icon(icon)
            .iconsByLoggerPrefixCsv(iconsByLogger)
            .warnTopic(warnTopic)
            // Unconditional, unlike the boxed setters below: the CSV overload is null-safe and
            // treats null/blank as "no keys", which is exactly the unset default.
            .includeMdcKeysCsv(includeMdcKeys);
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
    // Conditional like the other styling values: these carry non-null engine defaults, so passing
    // an unset setter straight through would null them out. warnTopic above is unconditional
    // because its default IS null — it is the opt-in switch.
    if (warnPriority != null) {
      builder.warnPriority(warnPriority);
    }
    if (warnTags != null) {
      builder.warnTags(warnTags);
    }
    if (clickUrl != null) {
      builder.clickUrl(clickUrl);
    }
    if (actions != null) {
      builder.actionsHeader(actions);
    }
    if (locale != null) {
      builder.locale(locale);
    }
    if (enabled != null) {
      builder.enabled(enabled);
    }
    if (async != null) {
      builder.asyncEnabled(async);
    }
    if (asyncQueueCapacity != null) {
      builder.asyncQueueCapacity(asyncQueueCapacity);
    }
    if (requireHttpsForCredentials != null) {
      builder.requireHttpsForCredentials(requireHttpsForCredentials);
    }
    if (startupPing != null) {
      builder.startupPing(startupPing);
    }
    if (startupPingFailFast != null) {
      builder.startupPingFailFast(startupPingFailFast);
    }
    if (startupPingWarn != null) {
      builder.startupPingWarn(startupPingWarn);
    }
    if (startupPingNotifyFailures != null) {
      builder.startupPingNotifyFailures(startupPingNotifyFailures);
    }
    return builder.build();
  }

  /**
   * Gates on level, then maps the event and hands it to the engine, which owns all further gating
   * and publishing. The gate lives here (mirroring the Quarkus adapter's SEVERE gate) so the
   * documented level contract holds on every install path — without it, a root-logger auto-install
   * would push every INFO/DEBUG line verbatim to the ntfy topic.
   *
   * <p>ERROR and above always alert. WARN alerts only when the engine activated a warn route; below
   * WARN nothing ever does, on any configuration. Sub-ERROR events therefore cost a level
   * comparison and nothing else in the default setup.
   */
  @Override
  protected void append(ILoggingEvent event) {
    AlertEngine e = engine;
    if (e == null) {
      return;
    }
    Level level = event.getLevel();
    if (level == null) {
      return;
    }
    AlertLevel routed;
    if (level.isGreaterOrEqual(Level.ERROR)) {
      routed = AlertLevel.ERROR;
    } else if (warnRoutingActive && level.isGreaterOrEqual(Level.WARN)) {
      routed = AlertLevel.WARN;
    } else {
      return;
    }
    e.submit(LogbackEventMapper.map(event, mdcKeys).withLevel(routed));
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
    // Torn down with the engine: a stopped appender holds no resolved configuration, so a restart
    // that fails to activate cannot keep projecting MDC values from the previous configuration —
    // nor keep routing WARN after a restart that withdrew the warn route.
    mdcKeys = List.of();
    warnRoutingActive = false;
    super.stop();
  }

  /**
   * Read-only pipeline observability counters (published / suppressed / failed) for this appender.
   * The returned holder is stable for the appender's whole lifetime — the same instance before and
   * after {@code start()}/{@code stop()} cycles — so the tallies are monotonic and never reset by a
   * stop or a Logback context reset/reattach. Counters are pulled here, never logged, preserving the
   * loop-safe design.
   */
  public PipelineCounters getCounters() {
    return counters;
  }
}
